package io.github.ashr123.walkietalkie.client;

import io.github.ashr123.walkietalkie.shared.protocol.ChannelMode;
import io.github.ashr123.walkietalkie.shared.protocol.JoinRequestInfo;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.io.InputStream;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/// Pins the window's TEXT and its gesture decision, without a display.
///
/// Everything asserted here is a static function of one [ClientSnapshot], which is why it can be tested at all — a
/// window is otherwise the least testable thing in a codebase. Keeping the derivations pure was the point: the frame
/// does nothing but push these strings into widgets on the Event Dispatch Thread.
///
/// The gesture matters more than the labels. `talkControl` is what makes press-and-hold possible, and getting it
/// wrong is not cosmetic: a hold that fires on a busy floor joins a queue the user did not ask to join, and a release
/// that fires when we never held the floor sends a release for something we were never given.
///
/// `grantOutlivedHold` and `applyState` are here for the same reason and were added after both went wrong unnoticed —
/// one left a microphone open with nobody holding anything, the other offered to re-key a channel to the passphrase it
/// already had. `applyState` had to be made static to be reachable at all: an instance of the window needs a display.
class SwingUiTest {

	private static final String SELF = "self-id";
	private static final String OTHER = "other-id";

	private static ClientSnapshot in(ChannelMode mode, String holder, List<String> waiting,
	                                 boolean queueOn, boolean transmitting, Set<String> muted) {
		return new ClientSnapshot(SELF, "team", mode, SELF, false, queueOn, false, transmitting,
				Map.of(SELF, "Me", OTHER, "Them"), muted,
				new WalkieClient.FloorSnapshot(holder, waiting), List.<JoinRequestInfo>of());
	}

	private static ClientSnapshot ptt(String holder, List<String> waiting) {
		return in(ChannelMode.MULTI_CHANNEL_PTT, holder, waiting, false, false, Set.of());
	}

	/// A snapshot of being in `channel` under `mode`, owned by `ownerId` — the three things the Apply decision reads.
	private static ClientSnapshot channelOwnedBy(String ownerId, String channel, ChannelMode mode) {
		return new ClientSnapshot(SELF, channel, mode, ownerId, false, false, false, false,
				Map.of(SELF, "Me", OTHER, "Them"), Set.of(), WalkieClient.FloorSnapshot.IDLE, List.of());
	}

	@Test
	void aFreeFloorIsAHoldAndAHeldFloorIsTheReleaseOfOne() {
		SwingUi.TalkControl free = SwingUi.talkControl(ptt(null, List.of()));
		assertTrue(free.hold(), "press-and-hold is the whole reason the window exists");
		assertTrue(free.enabled());
		assertEquals("Hold to talk", free.label());

		SwingUi.TalkControl live = SwingUi.talkControl(ptt(SELF, List.of()));
		assertTrue(live.hold());
		assertEquals("LIVE — release to stop", live.label());
	}

	@Test
	void aBusyFloorIsNotAHoldSoAPressCannotJoinAQueueByAccident() {
		SwingUi.TalkControl busy = SwingUi.talkControl(ptt(OTHER, List.of()));
		assertFalse(busy.hold(), "holding a busy floor must not act — that is what put people in queues they never asked for");
		assertFalse(busy.enabled());
		assertFalse(busy.raiseHandOffered(), "with the queue OFF there is no line to join");

		ClientSnapshot withQueue = in(ChannelMode.MULTI_CHANNEL_PTT, OTHER, List.of(), true, false, Set.of());
		SwingUi.TalkControl offered = SwingUi.talkControl(withQueue);
		assertFalse(offered.hold());
		assertTrue(offered.raiseHandOffered(), "joining a line is a TAP, and needs its own control");

		// "Busy" is not only "somebody is talking": a FREE floor that is RESERVED to another member's turn is just as
		// untouchable. Without this case the `waiting.isEmpty()` term in talkControl was pinned by nothing — deleting it
		// left the suite green while a hold started jumping the turn the server was holding for someone else.
		ClientSnapshot reserved = in(ChannelMode.MULTI_CHANNEL_PTT, null, List.of(OTHER), true, false, Set.of());
		SwingUi.TalkControl held = SwingUi.talkControl(reserved);
		assertFalse(held.hold(), "the floor is free but reserved, so a hold would steal another member's turn");
		assertFalse(held.enabled());
		assertEquals("Floor busy", held.label());
		assertTrue(held.raiseHandOffered(), "the queue is on, so raising a hand is the thing to offer instead");
	}

	@Test
	void aGrantThatOutlivedItsHoldIsEndedRatherThanLeftOpen() {
		// The race the browser answers with grantOpensMic: a press and its release both complete inside one round trip,
		// so the release found nothing to hand back and sent nothing — and the grant then opened the microphone with
		// `held` already cleared. Nothing else would ever have closed it; the server's idle sweep spares a holder who is
		// actively sending frames, so the room heard the user until the next press or the five-minute max-hold.
		ClientSnapshot talking = in(ChannelMode.MULTI_CHANNEL_PTT, SELF, List.of(), false, true, Set.of());
		SwingUi.TalkControl control = SwingUi.talkControl(talking);
		assertTrue(SwingUi.grantOutlivedHold(control, talking, false), "an open mic with nobody holding anything");
		assertFalse(SwingUi.grantOutlivedHold(control, talking, true),
				"a hold IN PROGRESS is the ordinary case, and cutting it off would end every transmission at once");

		ClientSnapshot duplex = in(ChannelMode.FULL_DUPLEX, null, List.of(), false, true, Set.of());
		assertFalse(SwingUi.grantOutlivedHold(SwingUi.talkControl(duplex), duplex, false),
				"full duplex is a deliberate tap — its open mic must survive every repaint");

		ClientSnapshot free = ptt(null, List.of());
		assertFalse(SwingUi.grantOutlivedHold(SwingUi.talkControl(free), free, false), "nothing is transmitting");
	}

	@Test
	void beingInLineOffersTheTapThatLeavesIt() {
		ClientSnapshot inLine = in(ChannelMode.MULTI_CHANNEL_PTT, OTHER, List.of(SELF), true, false, Set.of());
		SwingUi.TalkControl control = SwingUi.talkControl(inLine);
		assertFalse(control.hold(), "a hold would do nothing while queued");
		assertTrue(control.raiseHandOffered());
		assertEquals("In line to talk", control.label());
	}

	@Test
	void aReservedTurnIsAHold_soClaimingAndTalkingAreOneGesture() {
		// The head of a FREE floor: the server is holding it for us. One press claims it AND starts talking, which is
		// the sequence a console needs two keystrokes for.
		SwingUi.TalkControl mine = SwingUi.talkControl(ptt(null, List.of(SELF, OTHER)));
		assertTrue(mine.hold());
		assertEquals("YOUR TURN — hold to talk", mine.label());
	}

	@Test
	void fullDuplexIsATapBecauseThereIsNoFloorToHold() {
		SwingUi.TalkControl off = SwingUi.talkControl(
				in(ChannelMode.FULL_DUPLEX, null, List.of(), false, false, Set.of()));
		assertFalse(off.hold(), "a switch you must keep holding would be absurd for an always-open channel");
		assertTrue(off.enabled());
		assertEquals("Mic OFF — click to talk", off.label());

		SwingUi.TalkControl on = SwingUi.talkControl(
				in(ChannelMode.FULL_DUPLEX, null, List.of(), false, true, Set.of()));
		assertEquals("Mic ON — click to mute", on.label(), "the mic state is the only thing full-duplex can report");
	}

	@Test
	void beingMutedOrChannellessDisablesTheControlEntirely() {
		SwingUi.TalkControl muted = SwingUi.talkControl(
				in(ChannelMode.MULTI_CHANNEL_PTT, null, List.of(), false, false, Set.of(SELF)));
		assertFalse(muted.enabled());
		assertFalse(muted.hold(), "an owner-muted member must not be able to start a gesture at all");
		assertEquals("Muted by the channel owner", muted.label());

		ClientSnapshot nowhere = new ClientSnapshot(SELF, null, null, null, false, false, false, false,
				Map.of(), Set.of(), WalkieClient.FloorSnapshot.IDLE, List.of());
		assertFalse(SwingUi.talkControl(nowhere).enabled());
		assertEquals("Not in a channel", SwingUi.talkControl(nowhere).label());
	}

	@Test
	void theRosterPutsTheQueueFirstInItsOwnOrderAndEveryoneElseByName() {
		// The same two-section shape the browser settled on: a queue's meaning IS its order, so sorting it by name
		// would destroy the only information it carries.
		ClientSnapshot view = new ClientSnapshot(SELF, "team", ChannelMode.MULTI_CHANNEL_PTT, OTHER,
				false, true, false, false,
				Map.of(SELF, "Zoe", OTHER, "Adam", "c", "Bea"), Set.of("c"),
				new WalkieClient.FloorSnapshot(null, List.of(SELF, "c")), List.of());
		List<String> rows = SwingUi.rosterRows(view);

		assertEquals(3, rows.size());
		assertTrue(rows.get(0).startsWith("1. Zoe (you)"), rows.get(0));
		assertTrue(rows.get(0).endsWith("— offered the floor"), "the head of a free floor is being offered it: " + rows.get(0));
		assertTrue(rows.get(1).startsWith("2. Bea"), rows.get(1));
		assertTrue(rows.get(1).contains("🔇"), "a muted member reads as muted wherever they are: " + rows.get(1));
		assertTrue(rows.get(2).contains("Adam") && rows.get(2).contains("👑"), "the owner keeps the crown: " + rows.get(2));
	}

	@Test
	void aQueueEntryTheRosterCannotNameDoesNotShiftEverybodyElsesPosition() {
		// The state between a departing member's MemberLeft (which takes their name) and the FloorStatus that dequeues
		// them. The order filtered such an id out while the numbering counted the raw list, so — measured — the only
		// queued member was numbered "2.", no row was numbered 1, and a FREE floor was offered to nobody.
		ClientSnapshot view = new ClientSnapshot(SELF, "team", ChannelMode.MULTI_CHANNEL_PTT, OTHER,
				false, true, false, false, Map.of(SELF, "Me", OTHER, "Them"), Set.of(),
				new WalkieClient.FloorSnapshot(null, List.of("gone", SELF)), List.of());

		assertEquals(List.of(SELF, OTHER), SwingUi.rosterOrder(view), "an id with no name is not a row");
		List<String> rows = SwingUi.rosterRows(view);
		assertTrue(rows.get(0).startsWith("1. Me (you)"), "the queue is numbered from the rows actually shown: " + rows.get(0));
		assertTrue(rows.get(0).endsWith("— offered the floor"), "and the head of a free queue is being offered it: " + rows.get(0));
	}

	@Test
	void aTalkingMemberIsMarkedAndTheHeaderSaysWhereWeAre() {
		ClientSnapshot view = new ClientSnapshot(SELF, "team", ChannelMode.MULTI_CHANNEL_PTT, SELF,
				true, false, true, false, Map.of(SELF, "Me", OTHER, "Them"), Set.of(),
				new WalkieClient.FloorSnapshot(OTHER, List.of()), List.of());

		assertTrue(SwingUi.rosterRows(view).stream().anyMatch(row -> row.contains("Them") && row.contains("talking")));
		String header = SwingUi.headerText(view);
		assertTrue(header.startsWith("team · MULTI_CHANNEL_PTT · you own this channel"), header);
		assertTrue(header.contains("🔒 locked") && header.contains("🔇 arrivals muted"), header);
		assertFalse(header.contains("✋ queue on"), "the queue is off in this snapshot: " + header);
	}

	@Test
	void withNoChannelTheHeaderSaysSoRatherThanRenderingNulls() {
		ClientSnapshot nowhere = new ClientSnapshot(SELF, null, null, null, false, false, false, false,
				Map.of(), Set.of(), WalkieClient.FloorSnapshot.IDLE, List.of());
		assertEquals("Not in a channel.", SwingUi.headerText(nowhere));
	}

	// --- the buttons that must stay DEAD until pressing them would do something ------------------------------------

	@Test
	void renameIsOfferedOnlyOnceTheNameActuallyDiffers() {
		ClientSnapshot view = ptt(null, List.of());   // our confirmed name is "Me"
		assertFalse(SwingUi.renameOffered(view, "Me"), "unchanged: pressing it would ask the server for what it already has");
		assertFalse(SwingUi.renameOffered(view, "  Me  "), "trimmed to the same name, so still unchanged");
		assertFalse(SwingUi.renameOffered(view, "   "), "blank is not a name");
		assertTrue(SwingUi.renameOffered(view, "Roy"));
	}

	@Test
	void aRenameIsOfferedWhenWeHaveNoConfirmedNameYet() {
		// Before the first Joined the roster has no entry for us, so anything non-blank is a change.
		ClientSnapshot fresh = new ClientSnapshot(SELF, "team", ChannelMode.MULTI_CHANNEL_PTT, SELF,
				false, false, false, false, Map.of(), Set.of(), WalkieClient.FloorSnapshot.IDLE, List.of());
		assertTrue(SwingUi.renameOffered(fresh, "Roy"));
		assertFalse(SwingUi.renameOffered(fresh, ""));
	}

	@Test
	void anUntouchedFormHasNothingToApply_thoughTheKeyBoxIsFullFromTheCommandLine() {
		ClientSnapshot ours = channelOwnedBy(SELF, "team1", ChannelMode.MULTI_CHANNEL_PTT);
		// `--key` seeds the passphrase field, so "the box has characters in it" was never evidence of an intent to
		// rotate: Apply read "Apply changes" from the first frame of every ordinary session, and pressing it re-keyed
		// the channel — a PBKDF2 derivation and a PassphraseChanged broadcast to everyone — to the value already in use.
		assertEquals(SwingUi.ApplyState.NOTHING,
				SwingUi.applyState(ours, "team1", ChannelMode.MULTI_CHANNEL_PTT, false, false));
		assertEquals(SwingUi.ApplyState.NOTHING,
				SwingUi.applyState(ours, "", ChannelMode.MULTI_CHANNEL_PTT, false, false),
				"an empty channel field is not a switch to nowhere");
		assertEquals(SwingUi.ApplyState.ROTATE_PASSPHRASE,
				SwingUi.applyState(ours, "team1", ChannelMode.MULTI_CHANNEL_PTT, true, false),
				"a passphrase that DIFFERS is the rotation the button is for");
	}

	@Test
	void aSeededPassphraseIsNotAChangedOne_andAStrippedRotationStaysApplied() {
		// The decision the test above could only be handed, not exercise. `--key hunter2` fills the box at startup, so
		// "has characters" was never evidence of intent.
		assertFalse(SwingUi.passphraseChanged("hunter2", "hunter2"), "the seeded value is the one already in force");
		assertFalse(SwingUi.passphraseChanged("", "hunter2"), "blank is not a rotation — encryption cannot be turned off");
		assertFalse(SwingUi.passphraseChanged("   ", "hunter2"), "nor is whitespace");
		assertTrue(SwingUi.passphraseChanged("rotated", "hunter2"));
		assertTrue(SwingUi.passphraseChanged("hunter2", null), "with no key in force, any passphrase is a change");
		// Stripped on both sides, because changePassphrase strips before it stores. A trailing space is invisible in a
		// password field, and comparing raw text against the stored value left Apply enabled forever after the rotation
		// landed — every further click re-keying the channel to the value already in use.
		assertFalse(SwingUi.passphraseChanged("hunter2 ", "hunter2"), "a trailing space is not a different passphrase");
		assertFalse(SwingUi.passphraseChanged("hunter2", " hunter2"), "and neither is a stored one");
	}

	@Test
	void theWindowNamesItsOwnControlsRatherThanTheConsolesKeystroke() {
		// The console's coaching used to be welded into the client's status lines, so the window showed
		// "[floor free] — type 't' to talk" next to a button reading "Hold to talk", with no prompt to type at. The
		// FACT belongs to the client; the ACT belongs to whichever front end the user happens to be looking at.
		assertEquals("hold Talk", SwingUi.gestureFor(WalkieUi.Cue.TALK));
		assertEquals("let go", SwingUi.gestureFor(WalkieUi.Cue.STOP));
		assertEquals("press Raise hand", SwingUi.gestureFor(WalkieUi.Cue.JOIN_QUEUE),
				"holding Talk on a busy floor deliberately does nothing, so it must not be what we suggest");
		assertEquals("press Leave the line", SwingUi.gestureFor(WalkieUi.Cue.LEAVE_QUEUE));
		// Every cue must be answered: a new one added to the port would otherwise reach a window that says nothing.
		for (WalkieUi.Cue cue : WalkieUi.Cue.values()) {
			assertFalse(SwingUi.gestureFor(cue).isBlank(), "no wording for " + cue);
		}
	}

	@Test
	void spaceDrivesTheFloorUnlessSomethingWithABetterClaimOnItHasFocus() {
		// Why a dispatcher at all: an input map bound WHEN_IN_FOCUSED_WINDOW is consulted only when the focus owner has
		// not consumed the key, and every JButton, JCheckBox, JList and JComboBox binds SPACE. Clicking a roster row
		// used to kill push-to-talk outright, with the Talk button still looking able.
		assertTrue(SwingUi.drivesFloor(KeyEvent.VK_SPACE, ' ', KeyEvent.KEY_PRESSED, true, false),
				"a focused list or checkbox must not outrank the microphone");
		assertTrue(SwingUi.drivesFloor(KeyEvent.VK_UNDEFINED, ' ', KeyEvent.KEY_TYPED, true, false),
				"the typed leftover is claimed too — by character, since KEY_TYPED carries no key code");
		assertFalse(SwingUi.drivesFloor(KeyEvent.VK_SPACE, ' ', KeyEvent.KEY_PRESSED, true, true),
				"an EDITABLE field keeps its space bar — the one case where taking it would be indefensible");
		assertFalse(SwingUi.drivesFloor(KeyEvent.VK_SPACE, ' ', KeyEvent.KEY_PRESSED, false, false),
				"another application is in front; its space bar is not ours to take");
		assertFalse(SwingUi.drivesFloor(KeyEvent.VK_ENTER, '\n', KeyEvent.KEY_PRESSED, true, false));
		assertFalse(SwingUi.drivesFloor(KeyEvent.VK_UNDEFINED, 'x', KeyEvent.KEY_TYPED, true, false));
	}

	@Test
	void theQueueControlSaysWhetherItJoinsTheLineOrLeavesIt() {
		// It is offered to a member who is ALREADY queued — that is where the tap that leaves the line lives — so a
		// fixed "Raise hand" label described the opposite of what pressing it did, and pressing it lost your place.
		assertEquals("Raise hand ✋", SwingUi.raiseHandLabel(ptt(OTHER, List.of())));
		assertEquals("Leave the line ✋", SwingUi.raiseHandLabel(ptt(OTHER, List.of(SELF))));
		assertEquals("Raise hand ✋", SwingUi.raiseHandLabel(ptt(OTHER, List.of(OTHER))), "somebody else's place in line");
	}

	@Test
	void aMemberMayAdoptAnAnnouncedRotationThoughTheyMayNotStartOne() {
		ClientSnapshot theirs = channelOwnedBy(OTHER, "team1", ChannelMode.MULTI_CHANNEL_PTT);
		assertEquals(SwingUi.ApplyState.NOTHING,
				SwingUi.applyState(theirs, "team1", ChannelMode.MULTI_CHANNEL_PTT, true, false),
				"rotating a channel is the owner's alone, and the server would refuse it");
		// The exception, and the reason this is not simply an ownership test: when the owner has ANNOUNCED a rotation,
		// a member holds a key that no longer matches and their transmit gate drops every frame. Adopting the new
		// passphrase is the only non-owner use changePassphrase accepts — and this button is the only way to reach it,
		// so gating it on ownership stranded exactly the person the log had just told to enter a new passphrase.
		assertEquals(SwingUi.ApplyState.ROTATE_PASSPHRASE,
				SwingUi.applyState(theirs, "team1", ChannelMode.MULTI_CHANNEL_PTT, true, true));
	}

	@Test
	void aModeChangeBelongsToTheOwnerWhileASwitchBelongsToAnyone() {
		assertEquals(SwingUi.ApplyState.CHANGE_MODE, SwingUi.applyState(
				channelOwnedBy(SELF, "team1", ChannelMode.MULTI_CHANNEL_PTT), "team1", ChannelMode.FULL_DUPLEX, false, false));
		assertEquals(SwingUi.ApplyState.NOTHING, SwingUi.applyState(
						channelOwnedBy(OTHER, "team1", ChannelMode.MULTI_CHANNEL_PTT), "team1", ChannelMode.FULL_DUPLEX, false, false),
				"a member cannot change the channel's mode, so the button must not offer to");
		assertEquals(SwingUi.ApplyState.SWITCH, SwingUi.applyState(
						channelOwnedBy(OTHER, "team1", ChannelMode.MULTI_CHANNEL_PTT), "team2", ChannelMode.MULTI_CHANNEL_PTT, false, false),
				"a switch is a fresh join, which anyone may do");
	}

	@Test
	void theGlobalRoomIsPinnedSoTypingAnotherNameThereWouldDoNothing() {
		ClientSnapshot global = channelOwnedBy(OTHER, "global", ChannelMode.GLOBAL_PTT);
		// switchTo forces every GLOBAL_PTT join back to "global", so this used to light up "Switch channel" for a
		// switch the client then refused as "already in global" — and it was the only offered way out of the room.
		assertEquals(SwingUi.ApplyState.NOTHING,
				SwingUi.applyState(global, "team1", ChannelMode.GLOBAL_PTT, false, false));
		assertEquals(SwingUi.ApplyState.SWITCH,
				SwingUi.applyState(global, "team1", ChannelMode.MULTI_CHANNEL_PTT, false, false),
				"changing the MODE as well is what actually leaves the global room");
	}

	@Test
	void theWebClientsIconIsInTheJarAndDecodes() throws Exception {
		// The icon is not copied into this module — the build takes it from the browser client's own asset, so this
		// asserts the plumbing as much as the file: a renamed or moved favicon would break the desktop app's dock icon
		// with nothing else failing, and a missing image is the sort of thing nobody notices until it ships.
		try (InputStream source = SwingUi.class.getResourceAsStream("/walkie-icon.png")) {
			assertNotNull(source, "the build should have copied the browser client's icon into this jar");
			BufferedImage icon = ImageIO.read(source);
			assertNotNull(icon, "ImageIO must be able to decode it — which is why it is the PNG and not favicon.svg");
			// 180x180 is the apple-touch-icon size, and already what a macOS dock icon wants.
			assertEquals(180, icon.getWidth());
			assertEquals(180, icon.getHeight());
		}
	}

	@Test
	void theIconsBackgroundTileIsMadeTransparentWithoutEatingTheGlyph() throws Exception {
		// The measured numbers this rests on: the tile is #1b232d and covers 24573 of 32400 pixels, and the nearest
		// other colour is the speaker grille #0e151c at a distance of 25.6 — closer to the tile than to anything else
		// in the image. A colour key loose enough to catch the tile's antialiased edge would eat those grille lines,
		// which is why the fill is connectivity-based; this test is what would catch a regression back to a plain key.
		BufferedImage source;
		try (InputStream in = SwingUi.class.getResourceAsStream("/walkie-icon.png")) {
			source = ImageIO.read(in);
		}
		BufferedImage result = SwingUi.withoutTile(source);

		assertEquals(0, alphaAt(result, 0, 0), "the corner is tile, so it must be gone");
		assertEquals(0, alphaAt(result, 90, 1), "and so is the middle of the top edge");
		assertEquals(255, alphaAt(result, 90, 90), "the screen in the middle of the handset must survive");

		// The grille lines: dark, enclosed by the orange body, and the thing a naive colour key would destroy.
		int grilleKept = 0;
		for (int y = 0; y < result.getHeight(); y++) {
			for (int x = 0; x < result.getWidth(); x++) {
				if ((source.getRGB(x, y) & 0xffffff) == 0x0e151c) {
					grilleKept += alphaAt(result, x, y) == 255 ? 1 : 0;
				}
			}
		}
		assertEquals(856, grilleKept, "every one of the grille's pixels must still be opaque");

		long transparent = 0;
		for (int y = 0; y < result.getHeight(); y++) {
			for (int x = 0; x < result.getWidth(); x++) {
				transparent += alphaAt(result, x, y) == 0 ? 1 : 0;
			}
		}
		// The flat tile is 24573 pixels; the fill also takes its near-tile antialiased frontier, so allow a little
		// more, but nowhere near enough to have swallowed the 4912-pixel orange body.
		assertTrue(transparent >= 24_573 && transparent < 26_000,
				"expected roughly the tile to vanish, got " + transparent + " transparent pixels");
	}

	private static int alphaAt(BufferedImage image, int x, int y) {
		return image.getRGB(x, y) >>> 24;
	}
}
