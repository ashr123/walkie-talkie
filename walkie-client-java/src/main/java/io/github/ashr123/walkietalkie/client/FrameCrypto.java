package io.github.ashr123.walkietalkie.client;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;

/// End-to-end encryption for relay audio frames (AES-256-GCM).
///
/// The key is derived from a shared passphrase with PBKDF2-HMAC-SHA-512 (600 000 iterations), salted with
/// the effective channel name, so every client in a channel derives the same key and the server — which
/// relays frames opaquely — never sees it. Each frame is `scheme(1) ‖ IV(12) ‖ AES-256-GCM(key, IV, plaintext)`:
/// the leading scheme byte (`0xE2`, kept outside the codec-tag set so it never collides with a plaintext
/// `[codec tag][payload]` frame) lets a receiver distinguish an encrypted frame from a plaintext peer and
/// drop cleanly instead of decoding ciphertext as audio. The GCM output already carries the 16-byte tag;
/// the IV is a fresh 12 random bytes per frame. The scheme byte is also fed to AES-GCM as additional
/// authenticated data (AAD), so it is covered by the tag — a tampered or forged envelope fails decryption.
///
/// The wire format and parameters are mirrored exactly by the browser client's WebCrypto implementation
/// (see `app.js`); [FrameCryptoTest] pins a cross-platform known-answer vector so the two cannot drift.
final class FrameCrypto {

	private static final int IV_BYTES = 12;
	private static final int TAG_BITS = Byte.SIZE * 16;          // 16-byte GCM tag
	private static final byte SCHEME = (byte) 0xE2;              // wire marker: frame is scheme(1) ‖ IV ‖ ct+tag; kept outside the codec-tag set {1,2}
	private static final byte[] AAD = {SCHEME};                 // the scheme byte is authenticated (GCM AAD) but not encrypted, binding the envelope to the tag
	// PBKDF2 work factor: a deliberate slowdown so a low-entropy passphrase is expensive to brute-force
	// offline. It's the cost knob every password KDF has, not an arbitrary value — 600k comfortably exceeds
	// OWASP's PBKDF2-HMAC-SHA512 floor (210k). It must match the browser's WebCrypto iteration count exactly —
	// it's part of the cross-platform key-derivation contract.
	private static final int PBKDF2_ITERATIONS = 600_000;
	private static final int KEY_BITS = 32 * Byte.SIZE;
	private static final int KCV_BITS = 16 * Byte.SIZE;          // 16-byte key-check value, derived alongside the AES key
	private static final String SALT_PREFIX = "walkie-talkie:e2ee:";

	private final SecretKey key;
	private final String keyCheck;
	private final SecureRandom random = new SecureRandom();

	private FrameCrypto(SecretKey key, String keyCheck) {
		this.key = key;
		this.keyCheck = keyCheck;
	}

	/// Derives the per-channel key and its key-check value from the passphrase. `effectiveChannel` is the
	/// channel the server actually uses (`"global"` for global mode), so it must match what the other
	/// clients compute. A single PBKDF2 run yields `KEY_BITS + KCV_BITS` bits: the first 256 are the AES
	/// key, the next 128 are the [#keyCheck]. PBKDF2's first output block is independent of the requested
	/// length, so the AES key is byte-identical to deriving 256 bits alone — the known-answer test still holds.
	static FrameCrypto fromPassphrase(String passphrase, String effectiveChannel) throws GeneralSecurityException {
		byte[] secret = pbkdf2(passphrase, effectiveChannel, KEY_BITS + KCV_BITS);
		return new FrameCrypto(
				new SecretKeySpec(secret, 0, KEY_BITS / Byte.SIZE, "AES"),
				HexFormat.of().formatHex(secret, KEY_BITS / Byte.SIZE, (KEY_BITS + KCV_BITS) / Byte.SIZE)
		);
	}

	/// The 256-bit AES key bytes — only for the cross-platform known-answer test (it is the first `KEY_BITS`
	/// of the derivation; see [#fromPassphrase]).
	static byte[] deriveKeyBytes(String passphrase, String effectiveChannel) throws GeneralSecurityException {
		return pbkdf2(passphrase, effectiveChannel, KEY_BITS);
	}

	private static byte[] pbkdf2(String passphrase, String effectiveChannel, int bits) throws GeneralSecurityException {
		PBEKeySpec spec = new PBEKeySpec(
				passphrase.toCharArray(),
				(SALT_PREFIX + effectiveChannel).getBytes(StandardCharsets.UTF_8),
				PBKDF2_ITERATIONS,
				bits
		);
		try {
			return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA512").generateSecret(spec).getEncoded();
		} finally {
			spec.clearPassword();
		}
	}

	/// A short value confirming two clients derived the same key without revealing it: the server compares it
	/// across a channel's members and rejects a mismatch. Recovering the passphrase from it costs the same
	/// PBKDF2 work as brute-forcing a captured frame, so publishing it adds no practical exposure. `null` is
	/// carried by an unencrypted member.
	String keyCheck() {
		return keyCheck;
	}

	/// Encrypts a plaintext frame, returning `scheme(1) ‖ IV ‖ ciphertext+tag`.
	byte[] encrypt(byte[] plaintext) throws GeneralSecurityException {
		byte[] iv = new byte[IV_BYTES];
		random.nextBytes(iv);
		byte[] ciphertext = cipher(Cipher.ENCRYPT_MODE, iv).doFinal(plaintext);
		byte[] out = new byte[1 + IV_BYTES + ciphertext.length];
		out[0] = SCHEME;
		System.arraycopy(iv, 0, out, 1, IV_BYTES);
		System.arraycopy(ciphertext, 0, out, 1 + IV_BYTES, ciphertext.length);
		return out;
	}

	/// Decrypts a `scheme(1) ‖ IV ‖ ciphertext+tag` frame; throws on a missing scheme byte (a plaintext
	/// peer in an encrypted channel) or a bad tag (tampered, or wrong passphrase).
	byte[] decrypt(byte[] frame) throws GeneralSecurityException {
		if (frame.length < 1 + IV_BYTES + TAG_BITS / 8) {
			throw new GeneralSecurityException("frame too short to be encrypted");
		}
		if (frame[0] != SCHEME) {
			throw new GeneralSecurityException("not an end-to-end-encrypted frame (unencrypted peer or wrong scheme)");
		}
		return cipher(Cipher.DECRYPT_MODE, Arrays.copyOfRange(frame, 1, 1 + IV_BYTES))
				.doFinal(frame, 1 + IV_BYTES, frame.length - 1 - IV_BYTES);
	}

	/// Deterministic encryption with a caller-supplied IV, returning just the raw GCM output (ciphertext+tag,
	/// no scheme/IV envelope) — only for the cross-platform known-answer test, which pins the bare crypto.
	byte[] encryptWithIv(byte[] iv, byte[] plaintext) throws GeneralSecurityException {
		return cipher(Cipher.ENCRYPT_MODE, iv).doFinal(plaintext);
	}

	/// Wrap a passphrase under THIS key for an owner-initiated re-key: base64 of the frame envelope around the
	/// passphrase's UTF-8 bytes. A member that still holds this (old) key unwraps it to adopt the new passphrase
	/// automatically; the server relays the blob without ever seeing the passphrase. Same crypto/format as an
	/// audio frame, so it is byte-compatible with the browser's `wrapPassphrase` (and pinned by the same KAT).
	String wrap(String passphrase) throws GeneralSecurityException {
		return Base64.getEncoder().encodeToString(encrypt(passphrase.getBytes(StandardCharsets.UTF_8)));
	}

	/// Inverse of [#wrap]: recover the passphrase from a base64 wrapped blob. Throws if it was not wrapped under
	/// this key (a different/rotated key, or a tampered blob), so the caller falls back to a manual re-entry.
	String unwrap(String wrapped) throws GeneralSecurityException {
		return new String(decrypt(Base64.getDecoder().decode(wrapped)), StandardCharsets.UTF_8);
	}

	private Cipher cipher(int mode, byte[] iv) throws GeneralSecurityException {
		Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
		cipher.init(mode, key, new GCMParameterSpec(TAG_BITS, iv));
		cipher.updateAAD(AAD);   // bind the scheme byte into the tag — a tampered/forged envelope then fails the auth check
		return cipher;
	}
}
