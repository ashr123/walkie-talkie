package io.github.ashr123.walkietalkie.client;

import org.junit.jupiter.api.Test;

import java.security.GeneralSecurityException;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

/// Pins the client's outbound transmit-gate invariant: a client NEVER emits plaintext into a channel whose owner
/// has announced encryption. Exercises [WalkieClient#outboundFrame] directly (no live socket) across the
/// passphrase-rotation transitions — most importantly the plaintext→encrypted ENABLE case, where a not-yet-rekeyed
/// member holds no key and must go silent rather than leak cleartext.
class WalkieClientTest {

	/// A captured `[codec tag][payload]` plaintext frame (contents are arbitrary for this test).
	private static final byte[] FRAME = {1, 2, 3, 4, 5};

	private static final byte E2EE_SCHEME = (byte) 0xE2;   // first byte of an end-to-end-encrypted frame

	@Test
	void plaintextChannelSendsTheFrameInTheClear() throws GeneralSecurityException {
		// No key and no announced key-check = a genuinely unencrypted channel: send the frame as-is.
		assertArrayEquals(FRAME, WalkieClient.outboundFrame(FRAME, null, null));
	}

	@Test
	void enablingEncryptionMutesAMemberWithoutTheKey() throws GeneralSecurityException {
		// THE LEAK CASE: the owner just turned encryption ON (announced key-check is non-null) but this member
		// joined while the channel was plaintext and has not entered the new passphrase, so it holds no key.
		// It MUST emit nothing — never the plaintext frame — into the now-encrypted channel.
		assertNull(WalkieClient.outboundFrame(FRAME, null, "non-null-kcv"),
				"a not-yet-rekeyed member must emit NO frame into an announced-encrypted channel");
	}

	@Test
	void aStaleKeyAfterARotationIsMuted() throws GeneralSecurityException {
		// encrypted -> encrypted rotation: we still hold the OLD key, which no longer matches the channel's
		// announced key-check. We must stay SILENT (not emit stale-key audio the rekeyed channel can't decode and
		// not desync) until we adopt the new key — symmetric with everyone else.
		FrameCrypto staleKey = FrameCrypto.fromPassphrase("old-secret", "team");
		assertNull(WalkieClient.outboundFrame(FRAME, staleKey, "new-kcv-we-cannot-match"),
				"a member holding a stale key after a rotation it hasn't adopted must be muted");
	}

	@Test
	void switchingOutOfAnEncryptedChannelToAPlaintextOneStaysSilent() throws GeneralSecurityException {
		// During a channel switch the server still routes our audio to the OLD (encrypted) channel until it
		// processes the Join. switchTo clears the key for a plaintext target but deliberately leaves the OLD
		// channel's key-check in effect, so the gate must DROP rather than leak plaintext into the channel we are
		// leaving. (Same predicate as the enable case, pinned separately because it is a distinct real trigger.)
		assertNull(WalkieClient.outboundFrame(FRAME, null, "old-encrypted-channel-kcv"),
				"a switch out of an encrypted channel must not leak plaintext during the join round-trip");
	}

	@Test
	void aMatchingKeySendsCiphertext() throws GeneralSecurityException {
		// The owner's own seamless re-key (or a member that entered the new passphrase): key present -> ciphertext.
		FrameCrypto key = FrameCrypto.fromPassphrase("secret", "team");
		byte[] out = WalkieClient.outboundFrame(FRAME, key, key.keyCheck());
		assertNotNull(out);
		assertFalse(Arrays.equals(FRAME, out));
		assertEquals(E2EE_SCHEME, out[0]);
	}

	// --- rekeyAction: the announced-passphrase-change decision (never adopt a non-matching key) --------

	@Test
	void rekeyDisablesWhenTheAnnouncedKeyCheckIsNull() {
		// The owner turned encryption off — drop the key regardless of what we derived.
		assertEquals(WalkieClient.RekeyAction.DISABLE, WalkieClient.rekeyAction(null, null));
	}

	@Test
	void rekeyAppliesOnlyWhenTheDerivedKeyCheckMatches() throws GeneralSecurityException {
		FrameCrypto match = FrameCrypto.fromPassphrase("secret", "team");
		assertEquals(WalkieClient.RekeyAction.APPLY, WalkieClient.rekeyAction(match.keyCheck(), match));
	}

	@Test
	void rekeyKeepsTheOldKeyOnAMismatchOrMissingCandidate() throws GeneralSecurityException {
		// A non-matching derived key, or none at all, must KEEP the current key — never adopt a wrong key and
		// never (per outboundFrame) fall back to plaintext into an announced-encrypted channel.
		FrameCrypto wrong = FrameCrypto.fromPassphrase("the-wrong-secret", "team");
		assertEquals(WalkieClient.RekeyAction.KEEP, WalkieClient.rekeyAction("announced-kcv-we-cannot-match", wrong));
		assertEquals(WalkieClient.RekeyAction.KEEP, WalkieClient.rekeyAction("announced-kcv", null));
	}

	@Test
	void wrapRoundTripsThePassphraseAndOnlyTheKeyHolderCanUnwrap() throws GeneralSecurityException {
		// Owner-initiated auto-distribution: the new passphrase is wrapped under the OLD key; a member holding
		// that key recovers it, a member who doesn't can't.
		FrameCrypto oldKey = FrameCrypto.fromPassphrase("old-secret", "team");
		String wrapped = oldKey.wrap("the-new-passphrase");
		assertEquals("the-new-passphrase", oldKey.unwrap(wrapped), "the old key recovers the wrapped passphrase");
		FrameCrypto differentKey = FrameCrypto.fromPassphrase("some-other-key", "team");
		assertThrows(GeneralSecurityException.class, () -> differentKey.unwrap(wrapped),
				"only a holder of the wrapping (old) key can unwrap the new passphrase");
	}
}
