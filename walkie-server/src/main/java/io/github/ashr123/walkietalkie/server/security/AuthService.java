package io.github.ashr123.walkietalkie.server.security;

import io.github.ashr123.walkietalkie.server.config.WalkieProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.crypto.KeyGenerator;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

/// Stateless bearer auth for the demo: there is **no token store**. A login mints a self-contained,
/// HMAC-signed token; the [TokenAuthenticationFilter] verifies the signature (and expiry) at the
/// WebSocket handshake without any server-side lookup. Because nothing is stored, there is nothing to
/// revoke — closing the WebSocket ends the session, and a leaked token is only usable until it expires.
///
/// Token layout: `base64url(payload) + "." + base64url(HMAC-SHA512(key, payload))`, where
/// `payload = nonce(8 random bytes) ‖ expiryEpochMillis(8 bytes, big-endian)`. The token is opaque to
/// clients — they just echo it back as `Authorization: Bearer` or `?token=`.
///
/// In production the signing key comes from configuration (see [WalkieProperties#authSigningKey()]); this
/// would be replaced by validation against a real identity provider (OIDC/JWT) — the filter wiring stays.
@Service
public class AuthService {

	private static final Logger log = LoggerFactory.getLogger(AuthService.class);

	private static final String HMAC_ALGORITHM = "HmacSHA512";
	private static final int HMAC_KEY_BITS = 512;          // HMAC-SHA512 key strength (matches the 512-bit hash output)
	private static final int NONCE_BYTES = 8;              // random per token: unguessable and unique even within one millisecond
	private static final int EXPIRY_BYTES = Long.BYTES;    // 8-byte big-endian epoch-millis expiry
	private static final int PAYLOAD_BYTES = NONCE_BYTES + EXPIRY_BYTES;
	/// The token only has to survive from `/login` to the WebSocket upgrade — it is never re-checked on
	/// the live socket — so a short lifetime bounds replay of a leaked token without cutting active calls.
	private static final Duration TOKEN_TTL = Duration.ofSeconds(60);

	private static final Base64.Encoder B64_ENCODER = Base64.getUrlEncoder().withoutPadding();
	private static final Base64.Decoder B64_DECODER = Base64.getUrlDecoder();

	private final SecretKey key;
	private final SecureRandom random = new SecureRandom();

	public AuthService(WalkieProperties properties) {
		this.key = resolveKey(properties.authSigningKey());
	}

	private static SecretKey resolveKey(String configured) {
		if (configured != null && !configured.isBlank()) {
			return new SecretKeySpec(configured.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM);
		}
		try {
			KeyGenerator generator = KeyGenerator.getInstance(HMAC_ALGORITHM);
			generator.init(HMAC_KEY_BITS);
			log.warn("No walkie.auth-signing-key configured; generated a random HMAC key for this process. "
					+ "Tokens will not survive a restart or work across instances — set WALKIE_AUTH_SIGNING_KEY in production.");
			return generator.generateKey();
		} catch (GeneralSecurityException e) {
			throw new IllegalStateException(HMAC_ALGORITHM + " unavailable", e);
		}
	}

	/// Mints a fresh signed token valid for [#TOKEN_TTL]. No state is kept.
	public String issueToken() {
		byte[] payload = ByteBuffer.allocate(PAYLOAD_BYTES)
				.put(nonce())
				.putLong(Instant.now().plus(TOKEN_TTL).toEpochMilli())
				.array();
		return B64_ENCODER.encodeToString(payload) + "." + B64_ENCODER.encodeToString(mac(payload));
	}

	/// True when `token` is a well-formed, correctly-signed and unexpired token minted by this server.
	/// Verification is purely cryptographic — no lookup — and the MAC comparison is constant-time.
	public boolean verify(String token) {
		if (token == null) {
			return false;
		}
		int dot = token.indexOf('.');
		if (dot < 0) {
			return false;
		}
		try {
			byte[] payload = B64_DECODER.decode(token.substring(0, dot));
			return payload.length == PAYLOAD_BYTES && MessageDigest.isEqual(mac(payload), B64_DECODER.decode(token.substring(dot + 1)))
					&& Instant.now().isBefore(Instant.ofEpochMilli(ByteBuffer.wrap(payload, NONCE_BYTES, EXPIRY_BYTES).getLong()));
		} catch (IllegalArgumentException _) {   // malformed base64url
			return false;
		}
	}

	private byte[] nonce() {
		byte[] nonce = new byte[NONCE_BYTES];
		random.nextBytes(nonce);
		return nonce;
	}

	private byte[] mac(byte[] payload) {
		try {
			Mac hmac = Mac.getInstance(HMAC_ALGORITHM);
			hmac.init(key);
			return hmac.doFinal(payload);
		} catch (GeneralSecurityException e) {
			throw new IllegalStateException("HMAC computation failed", e);
		}
	}
}
