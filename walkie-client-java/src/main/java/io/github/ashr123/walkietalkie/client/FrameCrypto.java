package io.github.ashr123.walkietalkie.client;

import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.Key;
import java.security.SecureRandom;
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
	/// Everything before the ciphertext: the scheme marker plus the IV. Also the offset the GCM output starts at, which
	/// is why it earns a name instead of being spelled `1 + IV_BYTES` at each of its uses.
	private static final int HEADER_BYTES = 1 + IV_BYTES;
	/// The smallest an encrypted frame can be — header plus a bare tag, i.e. an empty plaintext. It is both the envelope
	/// [#encrypt] allocates around a payload and the length [#decrypt] refuses to go below, and it was previously
	/// written out twice with the tag size divided two different ways (`TAG_BITS / Byte.SIZE` in one, `TAG_BITS / 8` in
	/// the other) for the same 29 bytes. As one constant it also folds at compile time, so each use costs one add.
	private static final int ENVELOPE_BYTES = HEADER_BYTES + TAG_BITS / Byte.SIZE;
	// PBKDF2 work factor: a deliberate slowdown so a low-entropy passphrase is expensive to brute-force
	// offline. It's the cost knob every password KDF has, not an arbitrary value — 600k comfortably exceeds
	// OWASP's PBKDF2-HMAC-SHA512 floor (210k). It must match the browser's WebCrypto iteration count exactly —
	// it's part of the cross-platform key-derivation contract.
	private static final int PBKDF2_ITERATIONS = 600_000;
	private static final int KEY_BITS = Byte.SIZE * 32;
	private static final int KCV_BITS = Byte.SIZE * 16;          // 16-byte key-check value, derived alongside the AES key
	private static final String SALT_PREFIX = "walkie-talkie:e2ee:";

	/// One CSPRNG for the process rather than one per key, and `static` for a reason rather than by habit: GCM requires
	/// IV uniqueness per KEY, which a single shared generator gives as surely as a private one, and `SecureRandom` is
	/// thread-safe. A [FrameCrypto] is derived afresh on every join, channel switch and passphrase rotation, so a
	/// per-instance field meant constructing a generator each time for no benefit. Mirrors the server, which shares one
	/// `SecureRandom` bean across its security infrastructure for the same reason.
	///
	/// Declared as `SecureRandom` and NOT as the wider `RandomGenerator`, deliberately: this field's contract is
	/// cryptographic strength, and the narrow type is what makes the compiler enforce it. Both types expose
	/// `nextBytes(byte[])`, so widening buys nothing here — while it would let `RandomGenerator.getDefault()` or a
	/// `new Random()` past review, and a predictable IV is not a degradation in AES-GCM but a break: nonce reuse or
	/// prediction forfeits both confidentiality and the authentication tag.
	private static final SecureRandom RANDOM = new SecureRandom();
	private final String keyCheck;
	private final Key key;

	private FrameCrypto(Key key, String keyCheck) {
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
		RANDOM.nextBytes(iv);
		byte[] out = new byte[ENVELOPE_BYTES + plaintext.length];
		out[0] = SCHEME;
		System.arraycopy(iv, 0, out, 1, IV_BYTES);
		// Write the GCM ciphertext+tag straight into the envelope — no throwaway ciphertext array + second copy.
		cipher(Cipher.ENCRYPT_MODE, iv, 0).doFinal(plaintext, 0, plaintext.length, out, HEADER_BYTES);
		return out;
	}

	/// Decrypts a `scheme(1) ‖ IV ‖ ciphertext+tag` frame; throws on a missing scheme byte (a plaintext
	/// peer in an encrypted channel) or a bad tag (tampered, or wrong passphrase).
	byte[] decrypt(byte[] frame) throws GeneralSecurityException {
		if (frame.length < ENVELOPE_BYTES) {
			throw new GeneralSecurityException("frame too short to be encrypted");
		}
		if (frame[0] != SCHEME) {
			throw new GeneralSecurityException("not an end-to-end-encrypted frame (unencrypted peer or wrong scheme)");
		}
		// Point the GCM spec at the IV in place (frame[1 .. 1+IV_BYTES]) — no Arrays.copyOfRange.
		return cipher(Cipher.DECRYPT_MODE, frame, 1)
				.doFinal(frame, HEADER_BYTES, frame.length - HEADER_BYTES);
	}

	/// Deterministic encryption with a caller-supplied IV, returning just the raw GCM output (ciphertext+tag,
	/// no scheme/IV envelope) — only for the cross-platform known-answer test, which pins the bare crypto.
	byte[] encryptWithIv(byte[] iv, byte[] plaintext) throws GeneralSecurityException {
		return cipher(Cipher.ENCRYPT_MODE, iv, 0).doFinal(plaintext);
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

	private Cipher cipher(int mode, byte[] ivSource, int ivOffset) throws GeneralSecurityException {
		Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
		cipher.init(mode, key, new GCMParameterSpec(TAG_BITS, ivSource, ivOffset, IV_BYTES));
		cipher.updateAAD(AAD);   // bind the scheme byte into the tag — a tampered/forged envelope then fails the auth check
		return cipher;
	}
}
