// The push-to-talk floor rules and the Talk control's single decision. Pulled out of app.js into a DOM-free
// module — a sibling of e2ee.js — so it can be unit-tested under Node's built-in test runner (`node --test`, no
// npm dependencies) from src/test/js/talk.test.js. Nothing here touches the DOM or any browser global: it maps a
// plain snapshot of client state to what the Talk button should SAY and DO, and app.js renders that.
//
// floorStateFor, floorActionFor and shouldAutoOpenMic are the SAME pure rules the Java client applies —
// WalkieClient.floorStateFor / floorActionFor / shouldAutoOpenMic, pinned by WalkieClientTest — and the FLOOR_*
// values are that client's FloorState enum names, so the two must stay in lock-step. They take the same positional
// arguments as their Java counterparts for that reason; talkDecision takes a snapshot object instead only because
// it reads a dozen fields. The hold-vs-tap axis in talkDecision is genuinely browser-only: the Java console
// client's `t` is a single keystroke with no press/release edge to distinguish.

export const FLOOR_LIVE = 'LIVE';       // we hold the floor and are transmitting
export const FLOOR_MY_TURN = 'MY_TURN'; // reserved for us: the floor is free and we are the queue head — claim it
export const FLOOR_IN_LINE = 'IN_LINE'; // waiting further back in the queue
export const FLOOR_IDLE = 'IDLE';       // none of the above: floor free, held by another, or reserved for another

/**
 * Derives our floor state from the authoritative snapshot (holderId + waiting) and our own id — the same rule the
 * Java client's floorStateFor applies, so both stay in lock-step. There is deliberately no "reserved" field: the
 * member offered a free floor is exactly waiting[0] (the server reserves the head the instant the floor frees).
 *
 * The `== null` on the holder is loose on purpose, so a FloorStatus that omits the field reads as a free floor
 * rather than as held by `undefined`; don't tighten it to `===`.
 */
export function floorStateFor(self, holderId, waiting) {
	return self === holderId
		? FLOOR_LIVE
		: holderId == null && waiting.length > 0 && waiting[0] === self
			? FLOOR_MY_TURN
			: waiting.includes(self)
				? FLOOR_IN_LINE
				: FLOOR_IDLE;
}

/**
 * The message the state-driven Talk control sends for a floor state — mirrors the Java client's floorActionFor:
 * LIVE → releaseFloor (stop), IN_LINE → releaseFloor (leave the queue), MY_TURN → requestFloor (claim), IDLE →
 * requestFloor (grab if free, else enqueue when the queue is on; the server ignores it when busy + queue off).
 */
export function floorActionFor(floorState) {
	return floorState === FLOOR_LIVE || floorState === FLOOR_IN_LINE
		? {type: 'releaseFloor'}
		: {type: 'requestFloor'};
}

/**
 * Whether nobody holds OR is reserved for the floor — the only IDLE sub-case that stays a plain hold-to-talk. A
 * busy floor instead becomes tap-to-raise-hand when the queue is on, or a disabled "held by X" when it's off.
 */
export function floorIsFree(holderId, waiting) {
	return !holderId && waiting.length === 0;
}

/**
 * Whether joining a channel — or switching it to full-duplex — should open the microphone by itself: full-duplex
 * only, and only when the user didn't ask to start muted and the owner hasn't muted us. Mirrors the Java client's
 * shouldAutoOpenMic argument for argument. Push-to-talk never auto-opens; there the mic follows the floor.
 *
 * The owner-mute term earns its place on two real paths: a member re-joining its CURRENT channel re-snapshots
 * itself as muted, and a switch to full-duplex must not open a muted member's mic. It is not the enforcement
 * boundary — the server drops a muted sender's frames regardless — but without it the client would report a live
 * mic while every frame was being discarded.
 */
export function shouldAutoOpenMic(mode, startMuted, selfMuted) {
	return mode === 'FULL_DUPLEX' && !startMuted && !selfMuted;
}

/**
 * Whether an arriving `FloorGranted` should OPEN THE MIC — the other half of the "should the mic open by itself?"
 * question above, for the push-to-talk path where the mic follows the floor.
 *
 * A grant is an ANSWER, and the answer can outlive the question. Tap Space and let go inside one round trip and the
 * order of events is: requestFloor out, key up (which sends releaseFloor), grant in. Opening the mic on that grant
 * transmits audio AFTER the user let go — until our own releaseFloor comes back as a snapshot and the release
 * reconciliation shuts it again. Invisible on localhost, where the grant beats the key-up; a real leak of speech the
 * user believes was never sent as soon as there is any latency. So the mic opens only while the control is still held.
 *
 * This cannot refuse a legitimate tap-to-claim, because there is no such thing: every floor state whose grant opens
 * the mic is a 'hold' state (LIVE / MY_TURN / IDLE with a free floor — see talkDecision), and the two 'tap' states
 * only ever toggle queue membership. Claiming a reserved turn is itself a hold.
 *
 * Full-duplex is false rather than "not applicable": the server sends no grant where there is no floor, but a grant
 * can still LAND in full-duplex — request the floor in a push-to-talk channel whose owner switches the mode while
 * that request is in flight, and `ModeChanged` overtakes it. There the mic belongs to the user's own toggle, so a
 * leftover grant must not open it (and could not say so honestly: the button reads "Mic OFF (click to talk)").
 *
 * `talkHeld === true` rather than truthiness, because a value that isn't a definite yes must fail CLOSED — this
 * decides whether a microphone starts transmitting.
 */
export function grantOpensMic(mode, talkHeld) {
	return mode !== 'FULL_DUPLEX' && talkHeld === true;
}

/**
 * Whether a HOLD is in progress right now: the control is a hold-gesture one and it is actually being held. Two
 * callers ask, both about an interruption that has to give the floor back even though no ordinary up-edge described it:
 *   - losing focus (another window taking it, the tab being hidden, a cancelled touch), where the keyup or mouseup is
 *     delivered to whatever took focus and never reaches this page at all;
 *   - a Space up-edge that arrives while focus has DRIFTED onto some other control since the down-edge (hold Space,
 *     press Tab, release) — spaceDrivesFloor rightly says Space is not ours any more, but the floor we took on the
 *     down-edge still has to come back, or the mic stays open with nothing held.
 *
 * Both terms carry weight:
 *   - `talkHeld` is what stops an ordinary window switch from sending a releaseFloor. Alt-tabbing with nothing held is
 *     the common case by far; the server would treat those as no-ops, but a control message per focus change is noise
 *     on a plane whose budget is spent on real floor traffic.
 *   - `mode === 'hold'` is what stops it toggling QUEUE MEMBERSHIP. The two 'tap' states release-to-leave and
 *     request-to-join a line, so treating an interruption as a tap would silently drop you out of the queue you are
 *     waiting in. Full-duplex is excluded for the same class of reason in the other direction: its mic is a user
 *     toggle, and switching windows mid-conference must not mute you.
 *
 * `talkHeld === true` matches grantOpensMic's strictness: an indefinite value is not a hold in progress. That cannot
 * strand a live mic, because in push-to-talk the mic only opens on a grant that grantOpensMic already required a
 * definite hold for.
 */
export function holdInProgress(mode, talkHeld) {
	return talkHeld === true && mode === 'hold';
}

/** A decision with no floor message behind it: the disabled states and the full-duplex mic toggle. */
function floorless(mode, label) {
	return {mode, label, myTurn: false, action: null};
}

/**
 * The gesture instruction for each interaction mode: what to DO with the control, where the label says what it
 * currently IS. It depends on the mode alone — every state sharing a mode is operated the same way — which is why
 * talkDecision attaches it in one place rather than repeating it per branch.
 *
 * A disabled control gets none. There is no gesture to describe, and its label already gives the reason ("Muted by
 * owner", "Floor held by X", "Waiting to be admitted…"); app.js hides the element for an empty hint so the space
 * collapses. This replaces a line of static prose that told EVERY state to hold the button — including the two tap
 * states, where holding does nothing and a tap joins or leaves the queue.
 */
const MODE_HINTS = {
	hold: 'Hold the button — or hold Space — while you talk.',
	tap: 'Tap to join or leave the line; holding does nothing here.',
	duplex: 'Full-duplex: your mic stays open. Click to mute yourself.',
	disabled: ''
};

/**
 * The Talk control's ONE decision for a snapshot of client state — what the button says, whether a gesture does
 * anything, and which floor message that gesture sends.
 *
 *   mode   — the interaction mode from the design's unified-control table:
 *              'duplex'   full-duplex mic on/off toggle (no floor);
 *              'disabled' no action at all: not connected, in no channel, owner-muted, or a busy floor with the
 *                         queue OFF;
 *              'hold'     press-and-hold drives the floor/mic (LIVE, MY_TURN, IDLE + free);
 *              'tap'      a click toggles queue membership; press/release do NOT drive the mic (IN_LINE, IDLE +
 *                         busy + queue on).
 *   label  — the button's text.
 *   hint   — the gesture instruction shown under it (see MODE_HINTS), empty where there is no gesture.
 *   myTurn — drives the pulsing "your turn" highlight.
 *   action — the control message a gesture sends (floorActionFor of our floor state), or null where there is no
 *            floor to act on. Note a hold's RELEASE is always releaseFloor, not this; see app.js releaseTalk.
 *
 * The button's `disabled` is exactly `mode === 'disabled'` — that equivalence holds in every branch below, so
 * app.js derives it instead of this returning it twice.
 *
 * Returning the label and the gesture together is the point: they used to be two independent trees (talkMode and
 * updateTalkButton), and the presentation one was missing the in-no-channel case entirely, so the button could be
 * enabled and inviting for a state in which every gesture was a no-op.
 *
 * `view` is a plain snapshot: {connected, channel, pendingChannel, selfId, muted, mode, transmitting, floorHolder,
 * floorWaiting, floorQueueEnabled, claimSecondsLeft, labelFor}. `muted` is "the owner has muted ME"; `labelFor` is
 * a member-id → display-name function, called only for the busy-floor label so the roster stays out of here;
 * `floorWaiting` is expected to be an array (app.js normalises the snapshot on arrival).
 */
export function talkDecision(view) {
	const decision = decideTalk(view);
	return {...decision, hint: MODE_HINTS[decision.mode]};
}

function decideTalk(view) {
	// Order matters, and it is the Java client's order (toggleTalk tests "no channel" before the owner-mute).
	// Nothing resets the channel MODE on a disconnect or a refused join, so a channel-less client can still be
	// holding 'FULL_DUPLEX': testing membership below that branch would hand it a working mic toggle for a channel
	// it is not in.
	if (!view.connected) {
		return floorless('disabled', 'Connect first');
	}
	if (view.channel === null) {
		// Connected but in NO channel — waiting to be admitted to a locked one, or declined. There is no floor to
		// act on, so every talk gesture is a no-op.
		//
		// This guard is load-bearing, not cosmetic: a DISABLED button still dispatches mouseleave (browsers suppress
		// activation events like click/mousedown on it, but not enter/leave), so without it merely moving the cursor
		// off the greyed-out control took the hold-release path and sent a releaseFloor, earning a NOT_IN_CHANNEL
		// from the server once per pass without the user pressing anything.
		//
		// A knocker still holding a channel (an in-place switch into a locked one) does NOT land here: the server
		// gives up your current channel only once a join succeeds, so it keeps a working control for the channel it
		// is still in. That is why membership outranks the pending knock.
		return floorless('disabled', view.pendingChannel === null ? 'Not in a channel' : 'Waiting to be admitted…');
	}
	if (view.muted) {
		// The server drops our audio and refuses us the floor regardless, but disabling here stops us talking into a
		// closed door and makes the reason plain.
		return floorless('disabled', 'Muted by owner');
	}
	if (view.mode === 'FULL_DUPLEX') {
		return floorless('duplex', view.transmitting ? 'Mic ON (click to mute)' : 'Mic OFF (click to talk)');
	}
	// Push-to-talk: both the label and the gesture come from the authoritative floor snapshot.
	const floorState = floorStateFor(view.selfId, view.floorHolder, view.floorWaiting);
	const action = floorActionFor(floorState);
	switch (floorState) {
		case FLOOR_LIVE:
			return {mode: 'hold', label: 'LIVE — release to stop', myTurn: false, action};
		case FLOOR_MY_TURN:
			// Reserved for us (hold to claim). The countdown is display-only — the server owns the real window — and is
			// absent in two REACHABLE states, so neither arm is dead code: the snapshot that makes us the head always
			// arrives BEFORE the FloorReserved carrying the window (a protocol guarantee, see ServerMessage.
			// FloorReserved), so this renders suffix-less for that one message; and it drops the suffix again once the
			// ticker reaches 0, until the authoritative snapshot flips us away.
			return {
				mode: 'hold',
				label: view.claimSecondsLeft > 0
					? `YOUR TURN — hold to talk · ${view.claimSecondsLeft}s`
					: 'YOUR TURN — hold to talk',
				myTurn: true,
				action
			};
		case FLOOR_IN_LINE:
			// Waiting in line (tap to leave). Position is 1-based; self is in the queue by definition of IN_LINE.
			return {
				mode: 'tap',
				label: `In line #${view.floorWaiting.indexOf(view.selfId) + 1} of ${view.floorWaiting.length} — tap to leave`,
				myTurn: false,
				action
			};
		default: // IDLE
			if (floorIsFree(view.floorHolder, view.floorWaiting)) {
				return {mode: 'hold', label: 'Hold to talk', myTurn: false, action};
			}
			// Floor busy: with the queue ON, offer to raise a hand (tap to join the line); with it OFF, disable the
			// button and name whoever holds it — or, when the floor is merely RESERVED, whoever it is offered to.
			return view.floorQueueEnabled
				? {mode: 'tap', label: 'Raise hand ✋', myTurn: false, action}
				: floorless('disabled', `Floor held by ${view.labelFor(view.floorHolder || view.floorWaiting[0])}`);
	}
}
