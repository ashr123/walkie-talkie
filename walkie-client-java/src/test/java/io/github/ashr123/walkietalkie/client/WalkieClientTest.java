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
/// has announced encryption. Exercises [WalkieClient#outboundFrame] directly (no live socket).
///
/// The headline case used to be the plaintext→encrypted ENABLE transition. That transition no longer exists —
/// every channel but the server-managed `global` room is encrypted from creation, a join with no key-check is
/// refused with `PASSPHRASE_REQUIRED`, and a clearing rotation is refused too — so what these tests now pin is
/// the FAIL-CLOSED default itself: holding no key, or a stale one, for a channel that announces encryption emits
/// nothing. That matters more than it did, not less: with no legitimate way to reach a plaintext state, any
/// announcement that would put us in one is a downgrade attempt, and this gate is what refuses it.
class WalkieClientTest {

	/// A captured `[codec tag][payload]` plaintext frame (contents are arbitrary for this test).
	private static final byte[] FRAME = {1, 2, 3, 4, 5};

	private static final byte E2EE_SCHEME = (byte) 0xE2;   // first byte of an end-to-end-encrypted frame

	/// `plaintextAllowed` for the two kinds of channel, named so the call sites below read as the situation they are.
	private static final boolean GLOBAL_ROOM = true;
	private static final boolean NAMED_CHANNEL = false;

	@Test
	void theGlobalRoomSendsTheFrameInTheClear() throws GeneralSecurityException {
		// The one channel that is plaintext by design. Note the decision comes from the CALLER's mode, not from the
		// announced key-check — which is what stops a named channel ever reaching this branch.
		assertArrayEquals(FRAME, WalkieClient.outboundFrame(FRAME, null, null, GLOBAL_ROOM));
	}

	@Test
	void aMemberHoldingNoKeyForAnEncryptedChannelIsMuted() throws GeneralSecurityException {
		// THE LEAK CASE, and the fail-closed default: the channel announces a key-check and we hold no key at all.
		// No conformant server produces this state any more (it used to be the plaintext→encrypted enable), which
		// is exactly why the assertion is kept — a channel announcing a key we have nothing for is the shape a
		// downgrade attempt takes, and the answer must be silence, never the plaintext frame.
		assertNull(WalkieClient.outboundFrame(FRAME, null, "non-null-kcv", NAMED_CHANNEL),
				"a not-yet-rekeyed member must emit NO frame into an announced-encrypted channel");
	}

	@Test
	void holdingAKeyBeatsAModeClaimingTheChannelIsPlaintext() throws GeneralSecurityException {
		// The second term. `plaintextAllowed` is derived from the mode, and the mode arrives in the Joined snapshot,
		// so a server that lied about it could otherwise ask a member of a named channel to talk in the clear.
		// Holding a key is the client's own fact and means encryption was intended, so it wins.
		FrameCrypto key = FrameCrypto.fromPassphrase("secret", "team");
		assertNull(WalkieClient.outboundFrame(FRAME, key, null, GLOBAL_ROOM),
				"a claimed-global mode must not override a key we derived ourselves");
	}

	@Test
	void aNamedChannelAnnouncingNoKeyCheckIsMutedRatherThanSentInTheClear() throws GeneralSecurityException {
		// THE DOWNGRADE, and the reason this method takes a `plaintextAllowed` argument at all. Both of these used
		// to return the frame verbatim, because the gate read "unencrypted" off the SERVER-supplied key-check: one
		// forged `passphraseChanged { keyCheck: null }` therefore turned every member of an encrypted channel into
		// a cleartext transmitter. Outside the global room the answer must be silence whatever the server says.
		FrameCrypto key = FrameCrypto.fromPassphrase("secret", "team");
		assertNull(WalkieClient.outboundFrame(FRAME, key, null, NAMED_CHANNEL),
				"a forged clearing rotation must not talk us into sending in the clear");
		assertNull(WalkieClient.outboundFrame(FRAME, null, null, NAMED_CHANNEL),
				"nothing announced and nothing held is fail-closed in a named channel, not plaintext");
	}

	@Test
	void aStaleKeyAfterARotationIsMuted() throws GeneralSecurityException {
		// encrypted -> encrypted rotation: we still hold the OLD key, which no longer matches the channel's
		// announced key-check. We must stay SILENT (not emit stale-key audio the rekeyed channel can't decode and
		// not desync) until we adopt the new key — symmetric with everyone else.
		assertNull(WalkieClient.outboundFrame(FRAME, FrameCrypto.fromPassphrase("old-secret", "team"), "new-kcv-we-cannot-match", NAMED_CHANNEL),
				"a member holding a stale key after a rotation it hasn't adopted must be muted");
	}

	@Test
	void switchingOutOfAnEncryptedChannelToAPlaintextOneStaysSilent() throws GeneralSecurityException {
		// During a channel switch the server still routes our audio to the OLD (encrypted) channel until it
		// processes the Join. switchTo clears the key for a plaintext target but deliberately leaves the OLD
		// channel's key-check in effect, so the gate must DROP rather than leak plaintext into the channel we are
		// leaving. (Same predicate as the no-key case, pinned separately because it is a distinct real trigger.)
		assertNull(WalkieClient.outboundFrame(FRAME, null, "old-encrypted-channel-kcv", NAMED_CHANNEL),
				"a switch out of an encrypted channel must not leak plaintext during the join round-trip");
	}

	@Test
	void aMatchingKeySendsCiphertext() throws GeneralSecurityException {
		// The owner's own seamless re-key (or a member that entered the new passphrase): key present -> ciphertext.
		FrameCrypto key = FrameCrypto.fromPassphrase("secret", "team");
		byte[] out = WalkieClient.outboundFrame(FRAME, key, key.keyCheck(), NAMED_CHANNEL);
		assertNotNull(out);
		assertFalse(Arrays.equals(FRAME, out));
		assertEquals(E2EE_SCHEME, out[0]);
	}

	// --- rekeyAction: the announced-passphrase-change decision (never adopt a non-matching key) --------

	@Test
	void rekeyRefusesADowngradeWhenNoKeyCheckIsAnnounced() throws GeneralSecurityException {
		// This used to assert RekeyAction.DISABLE — "the owner turned encryption off, drop the key". That is gone
		// in both directions: the server refuses a clearing rotation with PASSPHRASE_REQUIRED, and obeying such an
		// announcement would be a downgrade (with the key dropped, outboundFrame sends in the clear). KEEP is the
		// fail-closed answer, and it must hold whatever we derived — including nothing.
		assertEquals(WalkieClient.RekeyAction.KEEP, WalkieClient.rekeyAction(null, null));
		assertEquals(WalkieClient.RekeyAction.KEEP, WalkieClient.rekeyAction(null, FrameCrypto.fromPassphrase("secret", "team")));
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

	// --- channel names: spaces allowed, and every spelling that LOOKS the same must reduce to one string ------

	@Test
	void aChannelNameMayContainPlainSpaces() {
		assertEquals("my room", WalkieClient.canonicalChannelName("my room"));
		assertEquals("\u05E9\u05DC\u05D5\u05DD \u05E2\u05D5\u05DC\u05DD",
				WalkieClient.canonicalChannelName("\u05E9\u05DC\u05D5\u05DD \u05E2\u05D5\u05DC\u05DD"));
	}

	@Test
	void everyVisibleWhitespaceSpellingCollapsesToOnePlainSpace() {
		// THE PARITY VECTOR, and the reason the collapsed set is written out rather than using `\\s`: Java's `\\s` is
		// ASCII-only while JavaScript's matches NBSP, so `\\s` on both sides would have the browser accept a name
		// this rejected. `\\p{Zs}` has identical membership in both languages. The SAME inputs are asserted in
		// names.test.js — if the two ever disagree, one of these two tests fails.
		//
		// Collapsing rather than rejecting is the better half of the bargain: someone who pasted an NBSP gets the
		// room everybody else is in instead of an error about a character they cannot see.
		java.util.List.of(
						"my room",              // plain space
						"my\u00A0room",         // NBSP
						"my\u3000room",         // ideographic space
						"my\u2009room",         // thin space
						"my\troom",             // tab
						"my   room",            // a run
						"  my \u00A0 room  ")    // mixed, with edges
				.forEach(spelling -> assertEquals("my room", WalkieClient.canonicalChannelName(spelling),
						() -> "should collapse to one room: " + spelling));
	}

	@Test
	void charactersNobodyCanRetypeAreStillRejected() {
		// ZWSP, the line/paragraph separators and the BOM are NOT in the collapsed set, so they survive
		// canonicalisation and are then refused by the allow-list — on both sides, identically. A channel name is a
		// rendezvous key with no id printed beside it, so a name holding one is a room nobody else can reach.
		java.util.List.of("my\u200Broom", "my\u2028room", "my\uFEFFroom")
				.forEach(name -> assertFalse(WalkieClient.isValidChannelName(name),
						() -> "should stay invalid: " + name));
	}

	@Test
	void theChannelCommandQuotesANameWithSpaces() {
		// `c <channel> [mode] [passphrase]` used to split on whitespace, which stopped working once a name could
		// contain one. Only the CHANNEL is quotable; the rest stays a single remainder because the passphrase may
		// itself contain spaces.
		assertArrayEquals(new String[]{"my room", "ptt secret"}, WalkieClient.splitChannelArgs("\"my room\" ptt secret"));
		assertArrayEquals(new String[]{"team-1", "ptt secret"}, WalkieClient.splitChannelArgs("team-1 ptt secret"));
		assertArrayEquals(new String[]{"team-1", ""}, WalkieClient.splitChannelArgs("  team-1  "));
		assertArrayEquals(new String[]{"my room", ""}, WalkieClient.splitChannelArgs("\"my room\""));
	}

	@Test
	void aPassphraseWithSpacesStillSurvivesTheChannelCommand() {
		// The behaviour that must NOT regress: the trailing passphrase was always the remainder of the line, so a
		// four-word passphrase worked. Splitting the whole line into tokens would have broken it silently.
		String[] split = WalkieClient.splitChannelArgs("\"my room\" ptt correct horse battery staple");
		assertEquals("my room", split[0]);
		String[] rest = split[1].split("\\s+", 2);
		assertEquals("ptt", rest[0]);
		assertEquals("correct horse battery staple", rest[1]);
	}

	@Test
	void anUnterminatedQuoteTakesTheRestOfTheLineAsTheName() {
		assertArrayEquals(new String[]{"my room ptt", ""}, WalkieClient.splitChannelArgs("\"my room ptt"));
	}
}
