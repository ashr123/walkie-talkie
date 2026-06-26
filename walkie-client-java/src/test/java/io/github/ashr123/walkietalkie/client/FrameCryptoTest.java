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
		byte[] ciphertext = FrameCrypto.fromPassphrase(PASSPHRASE, CHANNEL).encryptWithIv(IV, PLAINTEXT);
		assertEquals(
				"64d66fb60c1fe48c515bb15362b5bcd63cca8d0a48",
				HEX.formatHex(ciphertext),
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
}
