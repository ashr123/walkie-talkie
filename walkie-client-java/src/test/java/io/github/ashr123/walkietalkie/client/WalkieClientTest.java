package io.github.ashr123.walkietalkie.client;

import io.github.ashr123.walkietalkie.shared.protocol.ChannelMode;
import io.github.ashr123.walkietalkie.shared.protocol.ClientMessage;
import io.github.ashr123.walkietalkie.shared.protocol.ErrorCode;
import io.github.ashr123.walkietalkie.shared.protocol.ServerMessage;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.cfg.EnumFeature;
import tools.jackson.databind.json.JsonMapper;

import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.List;

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
		assertNull(WalkieClient.outboundFrame(FRAME, FrameCrypto.fromPassphrase("old-secret", "team"), "new-kcv-we-cannot-match"),
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
		assertEquals(
				WalkieClient.RekeyAction.KEEP,
				WalkieClient.rekeyAction(
						"announced-kcv-we-cannot-match",
						FrameCrypto.fromPassphrase("the-wrong-secret", "team")
				)
		);
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

	// --- full-duplex mic auto-open policy (shouldAutoOpenMic) ---------------------------------------

	@Test
	void fullDuplexAutoOpensTheMicByDefault() {
		// Full-duplex, no --muted, not owner-muted: the mic goes live as soon as you join (or switch to full-duplex).
		assertTrue(WalkieClient.shouldAutoOpenMic(ChannelMode.FULL_DUPLEX, false, false));
	}

	@Test
	void pushToTalkModesNeverAutoOpenTheMic() {
		// PTT/global require an explicit 't' to grab the floor — the mic never auto-opens, mute or not.
		assertFalse(WalkieClient.shouldAutoOpenMic(ChannelMode.MULTI_CHANNEL_PTT, false, false));
		assertFalse(WalkieClient.shouldAutoOpenMic(ChannelMode.GLOBAL_PTT, false, false));
	}

	@Test
	void startMutedKeepsTheMicClosedInFullDuplex() {
		// --muted: join full-duplex with the mic off until the user types 't'.
		assertFalse(WalkieClient.shouldAutoOpenMic(ChannelMode.FULL_DUPLEX, true, false));
	}

	@Test
	void ownerMutedKeepsTheMicClosedInFullDuplex() {
		// THE FIX: an owner-muted member's mic must NOT auto-open on a full-duplex join or mode change — otherwise
		// the client would report "mic is live" while the server drops every frame. Guards the Joined re-snapshot
		// (a muted member re-joining its current channel) and the ModeChanged-to-full-duplex path.
		assertFalse(WalkieClient.shouldAutoOpenMic(ChannelMode.FULL_DUPLEX, false, true));
	}

	@Test
	void anUnknownErrorCodeFromANewerServerDeserializesToTheUnknownFallback() {
		// Pins ErrorCode's forward-compatibility contract: a code this client's enum doesn't know (a NEWER server)
		// must degrade to the @JsonEnumDefaultValue fallback (UNKNOWN) instead of failing the whole message —
		// using the same EnumFeature the client's mapper enables (Jackson 3 hosts it there, not on
		// DeserializationFeature). Without the feature+annotation pair, this would throw and kill the listener.
		ServerMessage message = JsonMapper.builder()
				.enable(EnumFeature.READ_UNKNOWN_ENUM_VALUES_USING_DEFAULT_VALUE)
				.build()
				.readValue("""
						{"type":"error","code":"SOME_FUTURE_CODE","message":"from a newer server"}""", ServerMessage.class);
		assertInstanceOf(ServerMessage.ErrorMessage.class, message);
		assertEquals(ErrorCode.UNKNOWN, ((ServerMessage.ErrorMessage) message).code(),
				"an unrecognized code must fall back to UNKNOWN, not fail deserialization");
	}

	// --- floor-queue: deriving our state from a FloorStatus snapshot + the `t` decision it drives --------
	//
	// These pin the two PURE helpers behind the unified state-driven `t` control (the new push-to-talk floor queue):
	// floorStateFor derives our state from the authoritative FloorStatus (holderId + waiting) exactly as the design
	// specifies, and floorActionFor maps that state to the ClientMessage `t` sends. The FloorStatus-driven "I was
	// released -> stop the mic" reconciliation lives in handleFloorStatus, which mutates the live AudioEngine on the
	// listener thread; it is deliberately NOT unit-tested here (it would need a real capture line + threading and
	// would be brittle). The server owns the authoritative floor-transition coverage.

	@Test
	void floorStateIsLiveWhenWeHoldTheFloor() {
		assertEquals(WalkieClient.FloorState.LIVE, WalkieClient.floorStateFor("me", "me", List.of()));
		// Holding the floor wins even if we are (defensively) also listed in the queue.
		assertEquals(WalkieClient.FloorState.LIVE, WalkieClient.floorStateFor("me", "me", List.of("me")));
	}

	@Test
	void floorStateIsMyTurnWhenReservedAsTheFreeHead() {
		// Free floor (holderId == null) and we are the head of the queue: the server has reserved it for us — our turn.
		assertEquals(WalkieClient.FloorState.MY_TURN, WalkieClient.floorStateFor("me", null, List.of("me", "other")));
	}

	@Test
	void floorStateIsInLineWhenWaitingButNotTheHead() {
		// Free floor but someone else is the reserved head: we are further back in the line.
		assertEquals(WalkieClient.FloorState.IN_LINE, WalkieClient.floorStateFor("me", null, List.of("other", "me")));
		// Someone holds the floor and we are queued behind them: still IN_LINE (not the reserved head).
		assertEquals(WalkieClient.FloorState.IN_LINE, WalkieClient.floorStateFor("me", "holder", List.of("other", "me")));
	}

	@Test
	void floorStateIsIdleWhenUninvolved() {
		assertEquals(WalkieClient.FloorState.IDLE, WalkieClient.floorStateFor("me", null, List.of()));            // floor free
		assertEquals(WalkieClient.FloorState.IDLE, WalkieClient.floorStateFor("me", "holder", List.of()));        // busy, we're not queued
		assertEquals(WalkieClient.FloorState.IDLE, WalkieClient.floorStateFor("me", null, List.of("a", "b")));    // reserved for someone else
	}

	@Test
	void talkReleasesWhenLiveOrQueuedAndRequestsWhenClaimingOrGrabbing() {
		// The unified control's decision table: LIVE and IN_LINE give the floor/place up (ReleaseFloor); MY_TURN claims
		// and IDLE grabs-or-enqueues (RequestFloor). This is exactly what toggleTalk enqueues in a push-to-talk channel.
		assertInstanceOf(ClientMessage.ReleaseFloor.class, WalkieClient.floorActionFor(WalkieClient.FloorState.LIVE));
		assertInstanceOf(ClientMessage.ReleaseFloor.class, WalkieClient.floorActionFor(WalkieClient.FloorState.IN_LINE));
		assertInstanceOf(ClientMessage.RequestFloor.class, WalkieClient.floorActionFor(WalkieClient.FloorState.MY_TURN));
		assertInstanceOf(ClientMessage.RequestFloor.class, WalkieClient.floorActionFor(WalkieClient.FloorState.IDLE));
	}
}
