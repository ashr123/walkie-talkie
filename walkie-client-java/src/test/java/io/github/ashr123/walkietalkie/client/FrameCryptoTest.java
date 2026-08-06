package io.github.ashr123.walkietalkie.client;

import org.junit.jupiter.api.Test;

import java.security.GeneralSecurityException;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.*;

/// Verifies the relay-audio E2EE matches the browser's WebCrypto implementation. The two reference
/// vectors are produced by WebCrypto (Node's `crypto.subtle`, the same API `app.js` uses) for the fixed
/// inputs below; if the Java PBKDF2/AES-GCM ever drifts from the browser, these assertions fail.
class FrameCryptoTest {

	private static final HexFormat HEX = HexFormat.of();
	private static final String PASSPHRASE = "correct horse battery staple";
	private static final String CHANNEL = "lobby";
	/// A non-ASCII channel name, so the salt exercises multi-byte UTF-8. `שלום` = U+05E9 U+05DC U+05D5 U+05DD.
	private static final String HEBREW_CHANNEL = "\u05E9\u05DC\u05D5\u05DD";
	private static final byte[] IV = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11};
	private static final byte[] PLAINTEXT = {1, 16, 32, 48, 64};

	@Test
	void derivesTheSameKeyAsWebCrypto() throws GeneralSecurityException {
		assertEquals(
				"43321a28736472e94ff819ef9364476d5324b8fa550115409047f7da41fcbc06",
				HEX.formatHex(FrameCrypto.deriveKeyBytes(PASSPHRASE, CHANNEL)),
				"PBKDF2-HMAC-SHA512 (600k iters) must match the browser's derived AES key");
	}

	@Test
	void derivesTheSameKeyAsWebCryptoForANonAsciiChannel() throws GeneralSecurityException {
		// The salt is `"walkie-talkie:e2ee:" + channel`, so once channel names may be non-ASCII the two clients have
		// to agree on its BYTES: Java's String.getBytes(UTF_8) against the browser's TextEncoder. They should, and
		// this is the repo's habit of pinning that rather than assuming it — a disagreement here would surface to
		// users as a PASSPHRASE_MISMATCH for a passphrase that is provably identical. Vector generated with Node's
		// WebCrypto, like the ASCII ones above.
		assertEquals(
				"0573e6b667537933818594212c8772c3b2f0c1a79fa7430c45c66add8f9a1fd8",
				HEX.formatHex(FrameCrypto.deriveKeyBytes(PASSPHRASE, HEBREW_CHANNEL)),
				"a multi-byte UTF-8 salt must derive the same AES key in both clients");
		assertEquals(
				"adc9eaaf0fd50288934c090df35c4013",
				FrameCrypto.fromPassphrase(PASSPHRASE, HEBREW_CHANNEL).keyCheck(),
				"...and the same key-check, or the server's mismatch check misfires on identical passphrases");
	}

	@Test
	void normalisationIsWhatMakesTwoSpellingsOfOneChannelDeriveOneKey() throws GeneralSecurityException {
		// The failure NFC exists to prevent, pinned as arithmetic. Hebrew SHIN WITH SHIN DOT is a composition
		// EXCLUSION, so U+FB2A and the U+05E9 U+05C1 sequence render identically and normalise to the same string
		// while being different strings on the way in. Without canonicalisation two members typing the same visible
		// room name derive different keys and sit in one room hearing nothing.
		String precomposed = "\uFB2A\u05DC\u05D5\u05DD";
		String decomposed = "\u05E9\u05C1\u05DC\u05D5\u05DD";
		assertNotEquals(precomposed, decomposed, "the two spellings really are different strings");
		assertNotEquals(
				HEX.formatHex(FrameCrypto.deriveKeyBytes(PASSPHRASE, precomposed)),
				HEX.formatHex(FrameCrypto.deriveKeyBytes(PASSPHRASE, decomposed)),
				"un-normalised, the same visible name derives two different keys — this is the bug");
		assertEquals(
				HEX.formatHex(FrameCrypto.deriveKeyBytes(PASSPHRASE, WalkieClient.canonicalChannelName(precomposed))),
				HEX.formatHex(FrameCrypto.deriveKeyBytes(PASSPHRASE, WalkieClient.canonicalChannelName(decomposed))),
				"canonicalised, both spellings derive one key");
		// And that one key is the value the browser derives too (same vector in e2ee.test.js).
		assertEquals(
				"94de75c7866c774fbb6ea45f79a5185c95899b69732aabe25c7136ad0e571d69",
				HEX.formatHex(FrameCrypto.deriveKeyBytes(PASSPHRASE, WalkieClient.canonicalChannelName(precomposed))),
				"the canonical form's key must match the browser's");
	}

	@Test
	void derivesTheSameKeyCheckAsWebCrypto() throws GeneralSecurityException {
		// The key-check value is bytes 32..48 of the same 384-bit PBKDF2 derivation; both clients must agree
		// on it or the server's passphrase-mismatch check would misfire.
		assertEquals(
				"c9ea045aeadb2254fff7fa0efeb4d18a",
				FrameCrypto.fromPassphrase(PASSPHRASE, CHANNEL).keyCheck(),
				"the key-check value must match the browser's WebCrypto derivation");
	}

	@Test
	void encryptsToTheSameCiphertextAsWebCrypto() throws GeneralSecurityException {
		assertEquals(
				"64d66fb60c1fe48c515bb15362b5bcd63cca8d0a48",
				HEX.formatHex(FrameCrypto.fromPassphrase(PASSPHRASE, CHANNEL).encryptWithIv(IV, PLAINTEXT)),
				"AES-256-GCM (ciphertext+tag, scheme byte as AAD) must match the browser for the same key/IV/plaintext");
	}

	@Test
	void roundTrips() throws GeneralSecurityException {
		FrameCrypto crypto = FrameCrypto.fromPassphrase(PASSPHRASE, CHANNEL);
		assertArrayEquals(PLAINTEXT, crypto.decrypt(crypto.encrypt(PLAINTEXT)));
	}

	@Test
	void rejectsAFrameEncryptedWithADifferentPassphrase() throws GeneralSecurityException {
		byte[] frame = FrameCrypto.fromPassphrase(PASSPHRASE, CHANNEL).encrypt(PLAINTEXT);
		FrameCrypto wrongKey = FrameCrypto.fromPassphrase("a different passphrase", CHANNEL);
		assertThrows(GeneralSecurityException.class, () -> wrongKey.decrypt(frame));
	}

	@Test
	void rejectsAPlaintextPeersFrame() throws GeneralSecurityException {
		// A plaintext peer in an encrypted channel sends a long-enough [codec tag][payload] frame with no
		// scheme byte; decrypt must reject it (not try to decode ciphertext-as-audio), even with the right key.
		FrameCrypto crypto = FrameCrypto.fromPassphrase(PASSPHRASE, CHANNEL);
		byte[] frame = crypto.encrypt(PLAINTEXT);
		frame[0] = 1;   // CODEC_OPUS tag — i.e. a plaintext Opus frame, not the 0xE2 scheme byte
		assertThrows(GeneralSecurityException.class, () -> crypto.decrypt(frame));
	}

	@Test
	void derivesADifferentKeyPerChannel() throws GeneralSecurityException {
		// The key is salted on the channel name, so switching rooms (or the forced 'global') re-keys. A regression
		// to passphrase-only salting would still pass the single-channel KATs above yet silently reuse one key
		// across every room.
		assertNotEquals(
				FrameCrypto.fromPassphrase(PASSPHRASE, "room-a").keyCheck(),
				FrameCrypto.fromPassphrase(PASSPHRASE, "room-b").keyCheck(),
				"different channels must derive different key-checks");
		assertFalse(
				java.util.Arrays.equals(
						FrameCrypto.deriveKeyBytes(PASSPHRASE, "room-a"),
						FrameCrypto.deriveKeyBytes(PASSPHRASE, "room-b")),
				"different channels must derive different AES keys");
	}

	@Test
	void rejectsAFrameTooShortToBeEncrypted() throws GeneralSecurityException {
		FrameCrypto crypto = FrameCrypto.fromPassphrase(PASSPHRASE, CHANNEL);
		byte[] runt = {(byte) 0xE2, 1, 2, 3};   // scheme byte + a few bytes, well under the 1+12+16 = 29-byte minimum
		assertThrows(GeneralSecurityException.class, () -> crypto.decrypt(runt),
				"a frame too short to hold scheme+IV+tag must be rejected before reaching the cipher");
	}
}
