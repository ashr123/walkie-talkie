package io.github.ashr123.walkietalkie.server.security;

import io.github.ashr123.walkietalkie.server.config.WalkieProperties;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Pins the stateless signed-token contract: a freshly issued token verifies, while tampered, expired,
/// foreign-key and malformed tokens are rejected. (Security-critical — these are the only checks standing
/// between a request and the protected WebSocket endpoints, since there is no token store to fall back on.)
class AuthServiceTest {

	private static final String SIGNING_KEY = "test-signing-key-which-is-plenty-long";
	private static final SecureRandom RANDOM = new SecureRandom();
	private final AuthService authService = auth(SIGNING_KEY);

	private static AuthService auth(String signingKey) {
		return new AuthService(props(signingKey), RANDOM);
	}

	private static WalkieProperties props(String signingKey) {
		return new WalkieProperties(new String[]{"*"}, 8192, 65536, 100, 200, 5, 300, 10, false, signingKey, false);
	}

	@Test
	void acceptsAFreshlyIssuedToken() {
		assertTrue(authService.verify(authService.issueToken()), "a token we just minted must verify");
	}

	@Test
	void rejectsATamperedToken() {
		char[] chars = authService.issueToken().toCharArray();
		chars[0] = chars[0] == 'A' ? 'B' : 'A';   // perturb the payload segment -> the signature no longer matches
		assertFalse(authService.verify(new String(chars)), "a modified token must not verify");
	}

	@Test
	void rejectsAnExpiredToken() throws Exception {
		// Hand-craft a correctly-signed token (same key/format) whose expiry is in the past.
		byte[] payload = ByteBuffer.allocate(16)
				.putLong(0x0102030405060708L)                  // nonce (8 bytes)
				.putLong(System.currentTimeMillis() - 1_000)   // expiry: one second ago
				.array();
		Mac mac = Mac.getInstance("HmacSHA512");
		mac.init(new SecretKeySpec(SIGNING_KEY.getBytes(StandardCharsets.UTF_8), "HmacSHA512"));
		Base64.Encoder b64 = Base64.getUrlEncoder().withoutPadding();
		String expired = b64.encodeToString(payload) + "." + b64.encodeToString(mac.doFinal(payload));
		assertFalse(authService.verify(expired), "an expired but correctly-signed token must not verify");
	}

	@Test
	void rejectsATokenSignedWithADifferentKey() {
		String foreign = auth("a-completely-different-signing-key").issueToken();
		assertFalse(authService.verify(foreign), "a token signed with another key must not verify");
	}

	@Test
	void rejectsMalformedInput() {
		assertFalse(authService.verify("not-a-token"), "no dot separator");
		assertFalse(authService.verify("@@@.@@@"), "not valid base64url");
		assertFalse(authService.verify(""), "empty");
		assertFalse(authService.verify(null), "null");
	}

	@Test
	void rejectsACorrectlyEncodedButWrongLengthPayload() {
		// "QQ" is valid base64url (one decoded byte), so this clears the dot + base64 checks but fails the
		// fixed payload-length guard before any MAC work is done.
		assertFalse(authService.verify("QQ.QQ"), "a payload of the wrong length must not verify");
	}

	@Test
	void generatesARandomPerProcessKeyWhenNoneIsConfiguredSoTokensStillRoundTrip() {
		AuthService noKey = auth(null);
		assertTrue(noKey.verify(noKey.issueToken()), "a process-random key still signs and verifies");
		AuthService blankKey = auth("   ");
		assertTrue(blankKey.verify(blankKey.issueToken()), "a blank key takes the same random-fallback path");
	}
}
