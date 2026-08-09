// Browser-client tests for the Talk control's decision and the push-to-talk floor rules, run under Node's built-in
// test runner (`node --test`), no npm dependencies. Wired into the Gradle build via the `jsTest` task (guarded so
// it skips when Node isn't on PATH).
//
// talk.js is DOM-free by construction, so everything the Talk button says and does is pinned here as data: a
// snapshot of client state in, {mode, label, hint, myTurn, action} out. The button's `disabled` is derived by app.js as
// `mode === 'disabled'`, so asserting the mode asserts the greyed-out state too.
//
// floorStateFor / floorActionFor are the SAME pure rules the Java client applies, and the cases below mirror
// WalkieClientTest's, so a drift between the two clients fails on both sides.

import {test} from 'node:test';
import assert from 'node:assert/strict';

import {
	FLOOR_IDLE,
	FLOOR_IN_LINE,
	FLOOR_LIVE,
	FLOOR_MY_TURN,
	floorActionFor,
	floorIsFree,
	floorNarration,
	floorStateFor,
	grantOpensMic,
	holdInProgress,
	isVoiceActive,
	micTrackEnabled,
	needsVoiceMeter,
	queueView,
	SILENT,
	shouldAutoOpenMic,
	spaceDrivesFloor,
	talkDecision,
	TOO_QUICK_TO_TALK,
	VAD_RMS_THRESHOLD,
} from '../../main/resources/static/assets/talk.js';

const SELF = 'self-session-id';
const OTHER = 'other-session-id';
const HOLDER = 'holder-session-id';

const REQUEST = {type: 'requestFloor'};
const RELEASE = {type: 'releaseFloor'};

// The gesture instruction under the button, spelled out here rather than imported: the point is that the module
// says exactly this, and a hint read back from the module would pin nothing. NO_HINT is the disabled states, whose
// label already gives the reason — app.js hides the element for it.
const HOLD_HINT = 'Hold the button — or hold Space — while you talk.';
const TAP_HINT = 'Tap to join or leave the line; holding does nothing here.';
const DUPLEX_HINT = 'Full-duplex: your mic stays open. Click to mute yourself.';
const NO_HINT = '';

/**
 * A connected member of an ordinary PTT channel with a free floor — the state every case below varies ONE field of,
 * so each test names only what it is actually about.
 *
 * labelFor throws by default: the roster is consulted for exactly one label ("Floor held by X"), and a test that
 * doesn't opt in is asserting that it stays that way.
 */
function view(overrides) {
	return {
		connected: true,
		channel: 'team1',
		pendingChannel: null,
		selfId: SELF,
		muted: false,
		mode: 'MULTI_CHANNEL_PTT',
		transmitting: false,
		floorHolder: null,
		floorWaiting: [],
		floorQueueEnabled: false,
		claimSecondsLeft: 0,
		labelFor: id => assert.fail(`labelFor should not be consulted in this state (asked for ${id})`),
		...overrides
	};
}

// --- connected but in no channel: the regression this module was extracted for ---------------------

test('in no channel the control is disabled, not an inviting "Hold to talk"', () => {
	// THE motivating bug. A disabled button still dispatches mouseleave — browsers suppress activation events like
	// click/mousedown on it, but not enter/leave — so the mouseleave handler, whose only gate is `mode === 'hold'`,
	// ran on a cursor merely crossing the greyed-out control and released a floor in a channel we were not in. The
	// server answered NOT_IN_CHANNEL, once per pass, with nothing pressed.
	assert.deepEqual(talkDecision(view({channel: null})), {
		mode: 'disabled',
		label: 'Not in a channel',
		hint: NO_HINT,
		myTurn: false,
		action: null
	});
});

test('in no channel EVERY floor snapshot is still disabled, and never a hold', () => {
	// The guard has to dominate the whole floor tree, not just the free-floor leaf: narrowed to (say) an empty queue,
	// or sunk below the switch, it would survive a single-snapshot test while leaving the mouseleave path armed for
	// any state the server had last told us about.
	for (const floorHolder of [SELF, OTHER, null]) {
		for (const floorWaiting of [[], [SELF], [OTHER, SELF]]) {
			for (const floorQueueEnabled of [true, false]) {
				const decision = talkDecision(view({channel: null, floorHolder, floorWaiting, floorQueueEnabled}));
				// 'disabled' in particular is not 'hold', which is the mouseleave handler's only trigger.
				assert.equal(decision.mode, 'disabled', `holder=${floorHolder} waiting=[${floorWaiting}] queue=${floorQueueEnabled}`);
			}
		}
	}
});

test('in no channel a leftover FULL_DUPLEX mode does not hand back a working mic toggle', () => {
	// Reachable: neither the channel reset nor the disconnect teardown clears state.mode, so a client that was in a
	// full-duplex channel still holds 'FULL_DUPLEX' after a refused join. That is why membership is tested ABOVE the
	// full-duplex branch — the order the Java client also uses.
	assert.deepEqual(talkDecision(view({channel: null, mode: 'FULL_DUPLEX'})), {
		mode: 'disabled',
		label: 'Not in a channel',
		hint: NO_HINT,
		myTurn: false,
		action: null
	});
});

test('not connected reads the pre-connect label', () => {
	// Pins the initial markup (`<button disabled id="talkBtn">Connect first</button>`) and the disconnect teardown,
	// which used to poke this string in by hand. Without `connected` as an input the renderer could not tell "never
	// connected" from "connected, no channel".
	assert.deepEqual(talkDecision(view({connected: false, channel: null})), {
		mode: 'disabled',
		label: 'Connect first',
		hint: NO_HINT,
		myTurn: false,
		action: null
	});
});

test('waiting to be admitted beats the generic no-channel label', () => {
	// A fresh connection that knocked on a locked channel is parked, not refused. Swap the two guards and a knocker
	// is told it is simply "not in a channel" while the owner is still deciding.
	assert.equal(talkDecision(view({channel: null, pendingChannel: 'locked-room'})).label, 'Waiting to be admitted…');
});

test('a parked SWITCHER keeps a working control for the channel it is still in', () => {
	// The server gives up your current channel only once a join succeeds, so knocking on a locked channel from inside
	// another one must not disarm the control you are still using. Channel membership therefore outranks the knock.
	assert.deepEqual(talkDecision(view({pendingChannel: 'locked-room'})), {
		mode: 'hold',
		label: 'Hold to talk',
		hint: HOLD_HINT,
		myTurn: false,
		action: REQUEST
	});
});

test('a parked switcher that is LIVE can still stop talking', () => {
	// The strongest form of the case above: a member holding the floor must be able to release it while a knock is
	// outstanding. A pending-beats-everything ordering would leave a hot mic with no way to close it.
	assert.deepEqual(talkDecision(view({pendingChannel: 'locked-room', floorHolder: SELF, transmitting: true})), {
		mode: 'hold',
		label: 'LIVE — release to stop',
		hint: HOLD_HINT,
		myTurn: false,
		action: RELEASE
	});
});

// --- owner-enforced mute ---------------------------------------------------------------------------

test('owner-muted beats a free floor', () => {
	// The server drops a muted sender's audio and refuses it the floor anyway; disabling here stops the user talking
	// into a closed door and states the reason.
	assert.deepEqual(talkDecision(view({muted: true})), {
		mode: 'disabled',
		label: 'Muted by owner',
		hint: NO_HINT,
		myTurn: false,
		action: null
	});
});

test('owner-muted beats FULL_DUPLEX', () => {
	// Otherwise a muted full-duplex member gets an enabled "Mic ON" toggle for audio the server is discarding.
	assert.equal(talkDecision(view({muted: true, mode: 'FULL_DUPLEX', transmitting: true})).label, 'Muted by owner');
});

test('owner-muted beats LIVE', () => {
	// Muting force-releases the floor server-side, but MemberMuted can render before the FloorStatus that clears the
	// holder lands. In that window the mute must win, or the button invites a release that is already done.
	assert.equal(talkDecision(view({muted: true, floorHolder: SELF, transmitting: true})).mode, 'disabled');
});

test('owner-muted clears the "your turn" highlight', () => {
	// Without this a muted member keeps the pulsing highlight for a claim it can never make.
	assert.equal(talkDecision(view({muted: true, floorWaiting: [SELF], claimSecondsLeft: 8})).myTurn, false);
});

// --- full duplex: no floor, no queue ---------------------------------------------------------------

test('FULL_DUPLEX ignores the floor entirely', () => {
	// Full-duplex has no floor arbitration, so a snapshot left over from a pre-switch PTT channel must not disable
	// the mic toggle.
	assert.deepEqual(talkDecision(view({mode: 'FULL_DUPLEX', floorHolder: OTHER, floorWaiting: [HOLDER]})), {
		mode: 'duplex',
		label: 'Mic OFF (click to talk)',
		hint: DUPLEX_HINT,
		myTurn: false,
		action: null
	});
});

test('the FULL_DUPLEX label follows the mic, both ways', () => {
	// Asserting both arms is what kills a swapped ternary — and a swapped label here is a live footgun, since the
	// user would click to mute and open the mic instead.
	assert.equal(talkDecision(view({mode: 'FULL_DUPLEX', transmitting: true})).label, 'Mic ON (click to mute)');
	assert.equal(talkDecision(view({mode: 'FULL_DUPLEX', transmitting: false})).label, 'Mic OFF (click to talk)');
});

// --- the push-to-talk floor states ----------------------------------------------------------------

test('LIVE is a hold that releases', () => {
	// 'hold' is load-bearing twice over: it is what makes dragging the pointer off the button drop the floor rather
	// than leave a hot mic, and what makes a release stop the mic instead of leaving a queue.
	assert.deepEqual(talkDecision(view({floorHolder: SELF, transmitting: true})), {
		mode: 'hold',
		label: 'LIVE — release to stop',
		hint: HOLD_HINT,
		myTurn: false,
		action: RELEASE
	});
});

test('MY_TURN shows the claim countdown while it is running', () => {
	assert.deepEqual(talkDecision(view({floorWaiting: [SELF, OTHER], claimSecondsLeft: 7})), {
		mode: 'hold',
		label: 'YOUR TURN — hold to talk · 7s',
		hint: HOLD_HINT,
		myTurn: true,
		action: REQUEST
	});
});

test('MY_TURN drops the countdown cleanly rather than reading "0s"', () => {
	// The window is display-only — the server owns the real one — and is absent both before FloorReserved carries it
	// and again once the ticker clamps to 0. Dropping the `> 0` test would show "· 0s" for a turn still claimable.
	assert.equal(talkDecision(view({floorWaiting: [SELF], claimSecondsLeft: 0})).label, 'YOUR TURN — hold to talk');
});

test('MY_TURN is head-only: a mid-queue member is IN_LINE, not offered a claim', () => {
	// The reservation is DERIVED from being waiting[0]. Weaken it to "somewhere in the queue" and every queued member
	// gets the pulsing highlight, a hold gesture, and sends a requestFloor claiming a turn that isn't theirs.
	const decision = talkDecision(view({floorWaiting: [OTHER, SELF], floorQueueEnabled: true}));
	assert.equal(decision.myTurn, false);
	assert.deepEqual(decision, {
		mode: 'tap',
		label: 'In line #2 of 2 — tap to leave',
		hint: TAP_HINT,
		myTurn: false,
		action: RELEASE
	});
});

test('IN_LINE reports a 1-based position and leaves on a tap', () => {
	// Drop the `+ 1` and the second in line is told "#1 of 3" — indistinguishable from being next, so they stop
	// watching for their turn.
	assert.deepEqual(talkDecision(view({floorHolder: HOLDER, floorWaiting: [OTHER, SELF, 'third'], floorQueueEnabled: true})), {
		mode: 'tap',
		label: 'In line #2 of 3 — tap to leave',
		hint: TAP_HINT,
		myTurn: false,
		action: RELEASE
	});
});

test('IN_LINE at the head of a busy floor is a tap to leave, not an offered claim', () => {
	// The commonest queue state, and the one the two-name cases above can't reach: I raised the FIRST hand while
	// someone else is still talking. It is IN_LINE rather than MY_TURN because the floor is not free — so no pulsing
	// highlight, and no hold. Getting it wrong the other way is worse than cosmetic: 'hold' is the mouseleave
	// handler's only trigger, so a cursor crossing the button would drop the first hand-raiser out of the line.
	assert.deepEqual(talkDecision(view({floorHolder: HOLDER, floorWaiting: [SELF], floorQueueEnabled: true})), {
		mode: 'tap',
		label: 'In line #1 of 1 — tap to leave',
		hint: TAP_HINT,
		myTurn: false,
		action: RELEASE
	});
});

test('IN_LINE stays tappable, and still LEAVES the line, when the queue flag is already off', () => {
	// Reachable: FloorQueueChanged(false) can render before the FloorStatus that drains the line. A member still
	// listed must be able to get out, so the queue flag is consulted only in the IDLE-and-busy leaf — and the tap
	// has to send releaseFloor, or tapping re-requests the floor and the member cannot leave at all.
	assert.deepEqual(talkDecision(view({floorHolder: HOLDER, floorWaiting: [OTHER, SELF], floorQueueEnabled: false})), {
		mode: 'tap',
		label: 'In line #2 of 2 — tap to leave',
		hint: TAP_HINT,
		myTurn: false,
		action: RELEASE
	});
});

test('a free floor is hold-to-talk whether the queue is on or off', () => {
	// The queue flag is consulted ONLY when the floor is busy: with the queue on and nobody talking there is nothing
	// to queue behind, which is the resting state of every raise-hand channel. Treating it as a tap would stop
	// press-and-hold driving the mic there at all, and enqueue the user behind an empty line instead of just talking.
	const free = {mode: 'hold', label: 'Hold to talk', hint: HOLD_HINT, myTurn: false, action: REQUEST};
	assert.deepEqual(talkDecision(view({floorQueueEnabled: false})), free);
	assert.deepEqual(talkDecision(view({floorQueueEnabled: true})), free);
});

test('IDLE + busy + queue ON offers a raised hand', () => {
	// A tap, not a hold: holding here would drive the mic against a floor we do not own.
	assert.deepEqual(talkDecision(view({floorHolder: HOLDER, floorQueueEnabled: true})), {
		mode: 'tap',
		label: 'Raise hand ✋',
		hint: TAP_HINT,
		myTurn: false,
		action: REQUEST
	});
});

test('IDLE + busy + queue OFF is disabled and names the holder', () => {
	assert.deepEqual(talkDecision(view({floorHolder: HOLDER, labelFor: id => `Bob (#${id})`})), {
		mode: 'disabled',
		label: `Floor held by Bob (#${HOLDER})`,
		hint: NO_HINT,
		myTurn: false,
		action: null
	});
});

test('the busy-floor label names the live holder, not someone queued behind them', () => {
	// The only case where BOTH halves of "the holder, or else the member it is reserved for" are present: a member
	// talking with a hand already raised behind them, queue off. Prefer the wrong one and the button names a silent
	// waiter while somebody else is the one actually transmitting.
	const decision = talkDecision(view({
		floorHolder: HOLDER,
		floorWaiting: [OTHER],
		labelFor: id => (id === HOLDER ? 'Bob' : 'Ann')
	}));
	assert.equal(decision.label, 'Floor held by Bob');
});

test('a floor merely RESERVED for another is busy, not free', () => {
	// The sharpest mutation-killer for floorIsFree: weaken it from "no holder AND an empty queue" to "no holder" and
	// this reads as a free floor, letting us grab one the server has reserved for the queue head. The queue-off arm
	// also pins the `holder || waiting[0]` fallback — drop that half and the labeller is handed nothing.
	assert.deepEqual(talkDecision(view({floorWaiting: [OTHER], labelFor: id => `Ann (#${id})`})), {
		mode: 'disabled',
		label: `Floor held by Ann (#${OTHER})`,
		hint: NO_HINT,
		myTurn: false,
		action: null
	});
	assert.equal(talkDecision(view({floorWaiting: [OTHER], floorQueueEnabled: true})).mode, 'tap');
});

// --- the whole decision as one table --------------------------------------------------------------

/**
 * Every branch of the decision, with its COMPLETE expected output written out rather than derived from the
 * decision itself — an expectation read back off the returned object proves nothing. The focused tests above say
 * why each rule is what it is; this table is the flat oracle that no state escapes.
 */
const EVERY_STATE = [
	{name: 'not connected', view: {connected: false, channel: null}, expect: {mode: 'disabled', label: 'Connect first', hint: NO_HINT, myTurn: false, action: null}},
	{name: 'not connected but still holding a channel', view: {connected: false}, expect: {mode: 'disabled', label: 'Connect first', hint: NO_HINT, myTurn: false, action: null}},
	{name: 'no channel', view: {channel: null}, expect: {mode: 'disabled', label: 'Not in a channel', hint: NO_HINT, myTurn: false, action: null}},
	{name: 'waiting to be admitted', view: {channel: null, pendingChannel: 'locked-room'}, expect: {mode: 'disabled', label: 'Waiting to be admitted…', hint: NO_HINT, myTurn: false, action: null}},
	{name: 'muted, free floor', view: {muted: true}, expect: {mode: 'disabled', label: 'Muted by owner', hint: NO_HINT, myTurn: false, action: null}},
	{name: 'muted, full duplex', view: {muted: true, mode: 'FULL_DUPLEX'}, expect: {mode: 'disabled', label: 'Muted by owner', hint: NO_HINT, myTurn: false, action: null}},
	{name: 'muted while live', view: {muted: true, floorHolder: SELF, transmitting: true}, expect: {mode: 'disabled', label: 'Muted by owner', hint: NO_HINT, myTurn: false, action: null}},
	{name: 'muted while reserved', view: {muted: true, floorWaiting: [SELF], claimSecondsLeft: 5}, expect: {mode: 'disabled', label: 'Muted by owner', hint: NO_HINT, myTurn: false, action: null}},
	{name: 'duplex, mic on', view: {mode: 'FULL_DUPLEX', transmitting: true}, expect: {mode: 'duplex', label: 'Mic ON (click to mute)', hint: DUPLEX_HINT, myTurn: false, action: null}},
	{name: 'duplex, mic off', view: {mode: 'FULL_DUPLEX'}, expect: {mode: 'duplex', label: 'Mic OFF (click to talk)', hint: DUPLEX_HINT, myTurn: false, action: null}},
	{name: 'live', view: {floorHolder: SELF, transmitting: true}, expect: {mode: 'hold', label: 'LIVE — release to stop', hint: HOLD_HINT, myTurn: false, action: RELEASE}},
	{name: 'my turn, counting down', view: {floorWaiting: [SELF], claimSecondsLeft: 4}, expect: {mode: 'hold', label: 'YOUR TURN — hold to talk · 4s', hint: HOLD_HINT, myTurn: true, action: REQUEST}},
	{name: 'my turn, window lapsed', view: {floorWaiting: [SELF]}, expect: {mode: 'hold', label: 'YOUR TURN — hold to talk', hint: HOLD_HINT, myTurn: true, action: REQUEST}},
	{name: 'in line at the head, queue on', view: {floorHolder: HOLDER, floorWaiting: [SELF], floorQueueEnabled: true}, expect: {mode: 'tap', label: 'In line #1 of 1 — tap to leave', hint: TAP_HINT, myTurn: false, action: RELEASE}},
	{name: 'in line further back, queue on', view: {floorHolder: HOLDER, floorWaiting: [OTHER, SELF], floorQueueEnabled: true}, expect: {mode: 'tap', label: 'In line #2 of 2 — tap to leave', hint: TAP_HINT, myTurn: false, action: RELEASE}},
	{name: 'in line, queue off', view: {floorHolder: HOLDER, floorWaiting: [OTHER, SELF]}, expect: {mode: 'tap', label: 'In line #2 of 2 — tap to leave', hint: TAP_HINT, myTurn: false, action: RELEASE}},
	{name: 'idle, floor free, queue off', view: {}, expect: {mode: 'hold', label: 'Hold to talk', hint: HOLD_HINT, myTurn: false, action: REQUEST}},
	{name: 'idle, floor free, queue on', view: {floorQueueEnabled: true}, expect: {mode: 'hold', label: 'Hold to talk', hint: HOLD_HINT, myTurn: false, action: REQUEST}},
	{name: 'idle, floor free, global push-to-talk', view: {mode: 'GLOBAL_PTT', channel: 'global'}, expect: {mode: 'hold', label: 'Hold to talk', hint: HOLD_HINT, myTurn: false, action: REQUEST}},
	{name: 'idle, busy, queue on', view: {floorHolder: HOLDER, floorQueueEnabled: true}, expect: {mode: 'tap', label: 'Raise hand ✋', hint: TAP_HINT, myTurn: false, action: REQUEST}},
	{name: 'idle, reserved for another, queue on', view: {floorWaiting: [OTHER], floorQueueEnabled: true}, expect: {mode: 'tap', label: 'Raise hand ✋', hint: TAP_HINT, myTurn: false, action: REQUEST}},
	{name: 'idle, busy, queue off', view: {floorHolder: HOLDER, labelFor: () => 'Bob'}, expect: {mode: 'disabled', label: 'Floor held by Bob', hint: NO_HINT, myTurn: false, action: null}},
	{name: 'idle, busy with one queued behind, queue off', view: {floorHolder: HOLDER, floorWaiting: [OTHER], labelFor: () => 'Bob'}, expect: {mode: 'disabled', label: 'Floor held by Bob', hint: NO_HINT, myTurn: false, action: null}},
	{name: 'idle, reserved for another, queue off', view: {floorWaiting: [OTHER], labelFor: () => 'Ann'}, expect: {mode: 'disabled', label: 'Floor held by Ann', hint: NO_HINT, myTurn: false, action: null}}
];

test('the decision table: every state maps to exactly one mode, label, highlight and message', () => {
	for (const {name, view: overrides, expect} of EVERY_STATE) {
		assert.deepEqual(talkDecision(view(overrides)), expect, name);
	}
});

test('the hint follows the interaction mode, and only the mode', () => {
	// It answers "how do I work this control", which is the same answer for every state sharing a mode — so a state
	// must never carry a hint belonging to a different gesture. That is exactly what the static prose this replaced
	// got wrong: it told the two tap states to hold the button.
	const byMode = new Map();
	for (const {name, view: overrides} of EVERY_STATE) {
		const {mode, hint} = talkDecision(view(overrides));
		if (byMode.has(mode)) {
			assert.equal(hint, byMode.get(mode), `${name}: hint disagrees with another '${mode}' state`);
		}
		byMode.set(mode, hint);
	}
	assert.deepEqual([...byMode.entries()].sort(), [
		['disabled', NO_HINT],
		['duplex', DUPLEX_HINT],
		['hold', HOLD_HINT],
		['tap', TAP_HINT]
	]);
	// The three operable hints must be distinct and non-empty, or the field would be telling the user nothing.
	const operable = [HOLD_HINT, TAP_HINT, DUPLEX_HINT];
	assert.equal(new Set(operable).size, 3);
	operable.forEach(h => assert.ok(h.length > 0));
	// A disabled control has NO hint — index.html starts #talkHint empty and hidden to match, and app.js hides it
	// again whenever it is empty rather than leaving the element's top margin as a gap.
	assert.equal(NO_HINT, '');
});

test('no state falls outside the four interaction modes', () => {
	// app.js derives `btn.disabled = mode === 'disabled'` and the Space gate admits exactly 'hold' and 'tap', so a
	// fifth value would silently read as "not disabled, not actionable" — enabled-looking and inert, which is the
	// shape of the bug this module was extracted to prevent.
	for (const {name, view: overrides} of EVERY_STATE) {
		const {mode} = talkDecision(view(overrides));
		assert.ok(['duplex', 'disabled', 'hold', 'tap'].includes(mode), `${name}: unknown mode ${mode}`);
	}
});

test('the roster is consulted for one label only', () => {
	// A structural guard: the default labelFor throws, so this passes only while "Floor held by X" is the single
	// place member names enter the decision. It stops a future edit smuggling the roster into another branch — and
	// with it the staleness of a name that is resolved once and cached in a string.
	for (const {name, view: overrides} of EVERY_STATE) {
		if (!('labelFor' in overrides)) {   // the rows that supply one are exactly the three that name a holder
			talkDecision(view(overrides));   // throws via assert.fail if labelFor is touched
		}
	}
});

// --- the pure floor rules, in lock-step with the Java client's ------------------------------------
// These mirror WalkieClientTest's floorStateFor / floorActionFor cases one-for-one.

test('floorStateFor: holding the floor wins over also being queued', () => {
	// self === holder is tested first. A redundant or racing snapshot must not demote a live talker to IN_LINE, which
	// would flip the control to a tap and turn a release into "leave the queue".
	assert.equal(floorStateFor('me', 'me', []), FLOOR_LIVE);
	assert.equal(floorStateFor('me', 'me', ['me']), FLOOR_LIVE);
});

test('floorStateFor: the head of the queue on a free floor is MY_TURN', () => {
	// The reserved member is derived, not stored — the server reserves the head the instant the floor frees, so there
	// is deliberately no "reserved" field on the wire.
	assert.equal(floorStateFor('me', null, ['me', 'b']), FLOOR_MY_TURN);
});

test('floorStateFor: MY_TURN requires a free floor', () => {
	// Drop the null-holder conjunct and the head of the queue is offered a claim while another member is still
	// talking.
	assert.equal(floorStateFor('me', 'other', ['me']), FLOOR_IN_LINE);
});

test('floorStateFor: further back in the queue is IN_LINE', () => {
	assert.equal(floorStateFor('me', null, ['a', 'me']), FLOOR_IN_LINE);
	assert.equal(floorStateFor('me', 'holder', ['a', 'me']), FLOOR_IN_LINE);
});

test('floorStateFor: everything else is IDLE', () => {
	assert.equal(floorStateFor('me', null, []), FLOOR_IDLE);          // floor free
	assert.equal(floorStateFor('me', 'holder', []), FLOOR_IDLE);      // busy, we're not queued
	assert.equal(floorStateFor('me', null, ['a', 'b']), FLOOR_IDLE);  // reserved for someone else
});

test('floorStateFor: a snapshot that omits the holder reads as a free floor', () => {
	// The holder test is deliberately loose (`== null`), so a FloorStatus with the field absent is a free floor
	// rather than one held by `undefined`. Tightening it to `===` would break that.
	assert.equal(floorStateFor('me', undefined, ['me']), FLOOR_MY_TURN);
});

test('floorActionFor: the full four-way table', () => {
	// Read by both the hold down-edge and the tap up-edge, so one wrong cell means either leaving the queue when you
	// meant to join it or asking for the floor when you meant to stop talking.
	assert.deepEqual(floorActionFor(FLOOR_LIVE), RELEASE);
	assert.deepEqual(floorActionFor(FLOOR_IN_LINE), RELEASE);
	assert.deepEqual(floorActionFor(FLOOR_MY_TURN), REQUEST);
	assert.deepEqual(floorActionFor(FLOOR_IDLE), REQUEST);
});

test('floorIsFree: free means no holder AND nobody reserved', () => {
	assert.equal(floorIsFree(null, []), true);
	assert.equal(floorIsFree('x', []), false);
	assert.equal(floorIsFree(null, ['a']), false);   // reserved for the queue head — not ours to grab
	assert.equal(floorIsFree('x', ['a']), false);
});

// --- the full-duplex mic auto-open policy ---------------------------------------------------------
// One-for-one with WalkieClientTest's four shouldAutoOpenMic cases, so the two clients can't drift on when a
// microphone opens by itself.

test('shouldAutoOpenMic: full-duplex opens the mic by default', () => {
	// Full-duplex, no "Connect muted", not owner-muted: the mic goes live as soon as you join or switch into it.
	assert.equal(shouldAutoOpenMic('FULL_DUPLEX', false, false), true);
});

test('shouldAutoOpenMic: push-to-talk modes never auto-open the mic', () => {
	// PTT and global require an explicit talk gesture to take the floor — the mic never opens on its own there,
	// muted or not. Both PTT modes, so a mutation that only excludes one is caught.
	assert.equal(shouldAutoOpenMic('MULTI_CHANNEL_PTT', false, false), false);
	assert.equal(shouldAutoOpenMic('GLOBAL_PTT', false, false), false);
});

test('shouldAutoOpenMic: "Connect muted" keeps the mic closed in full-duplex', () => {
	// A connect-time choice: join full-duplex with the mic off until the user clicks the control.
	assert.equal(shouldAutoOpenMic('FULL_DUPLEX', true, false), false);
});

test('shouldAutoOpenMic: an owner-muted member\'s mic never auto-opens', () => {
	// The term that is easiest to drop, and the one with a user-visible cost: without it the control would report a
	// live mic ("Mic ON (click to mute)") while the server discarded every frame. It guards two real paths — a muted
	// member re-joining its CURRENT channel re-snapshots itself as muted, and a switch to full-duplex while muted.
	assert.equal(shouldAutoOpenMic('FULL_DUPLEX', false, true), false);
});

// --- whether an arriving grant opens the mic ------------------------------------------------------
// Browser-only, like the whole hold-vs-tap axis: the Java client's talk command is a toggle, so a grant there is
// always still wanted. What is pinned here is that a grant which outlived its hold cannot open a microphone.

test('grantOpensMic: a grant that arrives while the control is held goes live', () => {
	// The ordinary case, and both PTT modes, so a mutation that only handles one is caught.
	assert.equal(grantOpensMic('MULTI_CHANNEL_PTT', true), true);
	assert.equal(grantOpensMic('GLOBAL_PTT', true), true);
});

test('grantOpensMic: a grant that arrives after the user let go does NOT open the mic', () => {
	// THE bug this rule exists for. Tap Space and release inside one round trip and the order is: requestFloor out,
	// key-up (which sends releaseFloor), grant in. Opening the mic on that grant transmits speech AFTER the user let
	// go, until our own release comes back as a snapshot and the release reconciliation closes it again — invisible on
	// localhost, where the grant beats the key-up, and a real leak over any latency (it showed up first through a
	// tunnel). It also made the client report the resulting self-release as "you were released from the floor", the
	// wording reserved for the server taking it away.
	assert.equal(grantOpensMic('MULTI_CHANNEL_PTT', false), false);
	assert.equal(grantOpensMic('GLOBAL_PTT', false), false);
});

test('the too-quick message coaches instead of describing the mechanics', () => {
	// Spelled out here rather than imported, for the same reason as the gesture hints above: the point is that the
	// module says exactly this. It replaced "Floor granted after you let go — mic stayed off, floor released", which
	// described what the client did rather than what the user should do — and on a phone, where a tap is routinely
	// shorter than the round trip that fetches the floor, this is the line people see most.
	assert.equal(TOO_QUICK_TO_TALK, 'Too quick — hold the button while you talk');
});

test('grantOpensMic: full-duplex never opens the mic from a grant, held or not', () => {
	// Reachable, not defensive: request the floor in a PTT channel whose owner switches the mode while that request
	// is in flight and ModeChanged overtakes the grant. In full-duplex the mic belongs to the user's own toggle, so a
	// leftover grant must not open it — the button says "Mic OFF (click to talk)" and would be lying.
	assert.equal(grantOpensMic('FULL_DUPLEX', true), false);
	assert.equal(grantOpensMic('FULL_DUPLEX', false), false);
});

test('grantOpensMic: anything but a definite yes keeps the mic closed', () => {
	// This decides whether a microphone starts transmitting, so it fails CLOSED on a value that is merely truthy or
	// absent — `=== true`, not truthiness. A missing flag (a state object that never set it) must not open a mic, and
	// neither must a stringified one: the string 'false' is TRUTHY in JavaScript.
	[undefined, null, 'false', 'true', 1, 'yes', {}].forEach(held => {
		assert.equal(grantOpensMic('MULTI_CHANNEL_PTT', held), false, `talkHeld = ${JSON.stringify(held)}`);
	});
});

// --- what a floor snapshot is worth saying ---------------------------------------------------------
// FloorStatus is an authoritative snapshot re-sent on occasions that do not all MOVE the floor, so the narration
// has to describe a transition. The bug that prompted this: toggling the raise-hand queue logged "Floor is free"
// into a floor that was already free, once per toggle. Mirrored by WalkieClient.floorNarration, key for key.

const IDLE_VIEW = {
	selfId: SELF, holderId: null, waiting: [], released: false, awaitingClaim: false, floorQueueEnabled: false
};

test('floorNarration: the same situation twice yields the same key, so the second is silent', () => {
	// THE regression. The caller logs only on a key change, so two identical snapshots must agree on the key.
	assert.equal(floorNarration(IDLE_VIEW).key, floorNarration(IDLE_VIEW).key);
	assert.equal(floorNarration(IDLE_VIEW).key, 'free');
});

test('floorNarration: a real change produces a different key', () => {
	// The other half — suppression must not swallow anything that actually moved.
	assert.notEqual(floorNarration({...IDLE_VIEW, holderId: HOLDER}).key, floorNarration(IDLE_VIEW).key);
	assert.notEqual(floorNarration({...IDLE_VIEW, holderId: HOLDER}).key,
			floorNarration({...IDLE_VIEW, holderId: OTHER}).key, 'a different speaker is a different situation');
});

test('floorNarration: holding the floor or being offered it says nothing here', () => {
	// FloorGranted and FloorReserved are the imperative triggers for those; narrating them from the snapshot too
	// would talk over the alert on every queue churn.
	assert.equal(floorNarration({...IDLE_VIEW, holderId: SELF}), null, 'LIVE');
	assert.equal(floorNarration({...IDLE_VIEW, waiting: [SELF]}), null, 'MY_TURN');
});

test('floorNarration: a queue position is part of the situation', () => {
	// Moving from #3 to #2 IS news, so the key has to carry the position — not just "in line".
	const third = floorNarration({...IDLE_VIEW, holderId: HOLDER, waiting: [OTHER, 'x', SELF]});
	const second = floorNarration({...IDLE_VIEW, holderId: HOLDER, waiting: [OTHER, SELF]});
	assert.equal(third.kind, 'in-line');
	assert.equal(third.position, 3);
	assert.notEqual(third.key, second.key);
});

test('floorNarration: the transitions outrank the states that would otherwise be reported', () => {
	// A released holder sees "released", not "free"; a lapsed claim sees "your turn passed", not "free".
	assert.equal(floorNarration({...IDLE_VIEW, released: true}).kind, 'released');
	assert.equal(floorNarration({...IDLE_VIEW, awaitingClaim: true, floorQueueEnabled: true}).kind, 'turn-passed');
	assert.equal(floorNarration({...IDLE_VIEW, awaitingClaim: true, floorQueueEnabled: false}).kind, 'free',
			'with the queue switched off underneath us, FloorQueueChanged already explained the drop');
});

test('floorNarration: an offered floor names the head, and a held one names the holder', () => {
	assert.equal(floorNarration({...IDLE_VIEW, waiting: [OTHER]}).memberId, OTHER);
	assert.equal(floorNarration({...IDLE_VIEW, waiting: [OTHER]}).kind, 'offered');
	assert.equal(floorNarration({...IDLE_VIEW, holderId: HOLDER}).memberId, HOLDER);
	assert.equal(floorNarration({...IDLE_VIEW, holderId: HOLDER}).kind, 'talking');
});

test('floorNarration: the floor passing from one waiting member to the next is news', () => {
	// The head declining and the offer moving on is exactly the kind of change the queue exists to show, so the
	// offered member has to be IN the key. Without it both offers share the key 'offered' and the second is
	// suppressed — the queue would appear to stall on the first name.
	assert.notEqual(floorNarration({...IDLE_VIEW, waiting: [OTHER, HOLDER]}).key,
			floorNarration({...IDLE_VIEW, waiting: [HOLDER]}).key);
});

// --- is a hold in progress? ----------------------------------------------------------------------
// Asked by the two interruptions that must hand the floor back with no ordinary up-edge to describe them: focus lost
// to another window / a hidden tab / a cancelled touch, and a Space up-edge arriving after focus drifted onto another
// control mid-hold. Browser-only, like the rest of the hold axis.

test('holdInProgress: a held hold-gesture control is a hold in progress', () => {
	// The case that exists to stop a stale talkHeld: without this the flag stays true with nothing held, and then a
	// FloorReserved claims the floor unprompted or an arriving grant opens the mic (grantOpensMic's guard reads the
	// same flag, so it is only as honest as this).
	assert.equal(holdInProgress('hold', true), true);
});

test('holdInProgress: nothing held is no hold, so an ordinary window switch sends nothing', () => {
	// By far the common case — alt-tab while merely looking at the page. The server would no-op a release from a
	// non-holder, but this is what keeps a control message off the wire per focus change rather than relying on that.
	assert.equal(holdInProgress('hold', false), false);
});

test('holdInProgress: a tap gesture is never a hold, so an interruption cannot toggle queue membership', () => {
	// The tap states are release-to-LEAVE and request-to-JOIN a line, so taking the release path in one would drop
	// you out of the queue you are waiting in — a silent loss of your place, caused by looking at another window.
	assert.equal(holdInProgress('tap', true), false);
});

test('holdInProgress: full-duplex and disabled are never holds, so switching windows cannot mute you', () => {
	// Full-duplex's mic is the user's own toggle, and a conference keeps running while you read something else. The
	// disabled control has no gesture to end either.
	assert.equal(holdInProgress('duplex', true), false);
	assert.equal(holdInProgress('disabled', true), false);
});

test('holdInProgress: an indefinite held flag is not a hold in progress', () => {
	// Same strictness as grantOpensMic, and it cannot strand a live mic: in push-to-talk the mic only opens on a grant
	// that grantOpensMic already required a definite hold for, so "no release" here cannot leave one transmitting.
	[undefined, null, 'false', 1, 'yes'].forEach(held => {
		assert.equal(holdInProgress('hold', held), false, `talkHeld = ${JSON.stringify(held)}`);
	});
});

// --- whose key is Space? --------------------------------------------------------------------------
// Space is the activation key of a focused button and the open key of a focused select. The old gate claimed it
// globally except over INPUT, which left a keyboard user unable to open the Channel mode dropdown at all.

const BODY = {tagName: 'BODY', isTalkButton: false};
const TALK_BUTTON = {tagName: 'BUTTON', isTalkButton: true};

test('spaceDrivesFloor: Space drives the floor when no control owns it', () => {
	// Focus parked on the document — where it sits after a click on any non-focusable part of the page — and the case
	// where nothing is focused at all (activeElement can be null).
	assert.equal(spaceDrivesFloor('hold', BODY), true);
	assert.equal(spaceDrivesFloor('hold', {tagName: 'HTML', isTalkButton: false}), true);
	assert.equal(spaceDrivesFloor('hold', null), true);
	assert.equal(spaceDrivesFloor('hold', undefined), true);
});

test('spaceDrivesFloor: the Talk button keeps Space after you click it', () => {
	// The common flow: click Talk once with the mouse, then keep talking with Space. Safe precisely because that
	// button has no click handler — it drives the floor from mousedown/mouseup — so the activation Space would
	// otherwise trigger does nothing. Identified by identity, not by tag: a BUTTON that is NOT it is excluded below.
	assert.equal(spaceDrivesFloor('hold', TALK_BUTTON), true);
	assert.equal(spaceDrivesFloor('tap', TALK_BUTTON), true);
});

test('spaceDrivesFloor: a focused select keeps Space, so its dropdown still opens', () => {
	// THE accessibility bug. Transport, Channel mode and the owner's transfer-ownership dropdown: Space is how you
	// open them, and taking the floor plus preventDefault() left them looking simply broken to a keyboard user.
	assert.equal(spaceDrivesFloor('hold', {tagName: 'SELECT', isTalkButton: false}), false);
	assert.equal(spaceDrivesFloor('tap', {tagName: 'SELECT', isTalkButton: false}), false);
});

test('spaceDrivesFloor: any other focused button keeps Space, so it activates instead of talking', () => {
	// Rename, Apply, Mute everyone now, Admit/Deny, the Lock toggle: Space is a button's activation key, and the two
	// actions used to race with the winner decided by whether the keydown was prevented.
	assert.equal(spaceDrivesFloor('hold', {tagName: 'BUTTON', isTalkButton: false}), false);
});

test('spaceDrivesFloor: text entry surfaces keep Space — including ones nobody remembered to list', () => {
	// INPUT was the only exclusion the old gate had. TEXTAREA and a contenteditable host are covered here for free
	// because this is an allow-list: the page has no textarea today, and adding one must not silently take Space.
	[
		{tagName: 'INPUT', isTalkButton: false},
		{tagName: 'TEXTAREA', isTalkButton: false},
		{tagName: 'DIV', isTalkButton: false},
		{tagName: 'A', isTalkButton: false}
	].forEach(focus => {
		assert.equal(spaceDrivesFloor('hold', focus), false, focus.tagName);
	});
});

test('spaceDrivesFloor: modes with no floor gesture ignore Space wherever focus is', () => {
	// Full-duplex toggles its mic with a click and a disabled control has no gesture, so Space must fall through to
	// whatever the page would do with it — scrolling, typically — rather than being swallowed.
	assert.equal(spaceDrivesFloor('duplex', BODY), false);
	assert.equal(spaceDrivesFloor('disabled', BODY), false);
	assert.equal(spaceDrivesFloor('duplex', TALK_BUTTON), false);
});

test('the FLOOR_* values are the Java client\'s FloorState enum names', () => {
	// Same reason the E2EE suite pins the Java known-answer vectors: these strings are the shared vocabulary of a
	// rule both clients are documented to apply identically.
	assert.equal(FLOOR_LIVE, 'LIVE');
	assert.equal(FLOOR_MY_TURN, 'MY_TURN');
	assert.equal(FLOOR_IN_LINE, 'IN_LINE');
	assert.equal(FLOOR_IDLE, 'IDLE');
});

// --- micTrackEnabled: the local microphone track follows the FLOOR, and nothing else --------------------

test('micTrackEnabled is exactly "am I transmitting?" — no transport term, no mode term', () => {
	// The property, stated as the invariant rather than as a table: a track is a capture device, so where its
	// samples go cannot change whether the floor says the mic is open. This replaced
	// `transport === 'webrtc' ? on : true`, which forced a relay client's track ENABLED at every disable site —
	// including endTransmit and the owner-muted branch of beginTransmit. Combined with a relay client answering any
	// WebRTC offer it was handed, that left a microphone streaming to a peer outside the server's floor and
	// owner-mute enforcement and outside the passphrase E2EE, with both UIs showing a free floor.
	assert.equal(micTrackEnabled(true), true);
	assert.equal(micTrackEnabled(false), false);
});

test('micTrackEnabled fails closed on anything that is not a definite yes', () => {
	// Same discipline as grantOpensMic: an absent or non-boolean value must never open a microphone. `createPeer`
	// calls this with state.transmitting, which is undefined for the window before the first join completes.
	[undefined, null, 0, 1, '', 'true', 'yes', {}, []].forEach(value =>
			assert.equal(micTrackEnabled(value), false, `${JSON.stringify(value) ?? String(value)} must not open the mic`));
});

test('micTrackEnabled agrees with shouldAutoOpenMic on the full-duplex auto-open', () => {
	// createPeer used to carry its own `mode === 'FULL_DUPLEX' ||` disjunct, which ignored "Connect muted" and
	// owner-mute. The two functions now have distinct jobs and must not be conflated: shouldAutoOpenMic decides
	// whether to START transmitting (weighing mode, --muted and owner-mute), and micTrackEnabled reflects whether we
	// ARE. Full-duplex therefore still opens the mic — via beginTransmit, one step later — but only when
	// shouldAutoOpenMic agrees.
	assert.equal(micTrackEnabled(shouldAutoOpenMic('FULL_DUPLEX', false, false)), true);
	assert.equal(micTrackEnabled(shouldAutoOpenMic('FULL_DUPLEX', true, false)), false, '--muted must stay muted');
	assert.equal(micTrackEnabled(shouldAutoOpenMic('FULL_DUPLEX', false, true)), false, 'owner-muted must stay muted');
	assert.equal(micTrackEnabled(shouldAutoOpenMic('MULTI_CHANNEL_PTT', false, false)), false, 'PTT never auto-opens');
});

// --- the roster "who is talking" highlight: which driver covers which (transport, mode) -------------------

test('needsVoiceMeter covers exactly the combination that had NO highlight driver', () => {
	// Measured before the fix, with two real browser clients per combination: WebRTC + FULL_DUPLEX never lit a
	// single row for anyone, while every other pairing did. The relay decode lanes highlight a remote sender and
	// the relay capture path highlights you; onFloorStatus highlights the floor holder on WebRTC. Full-duplex has
	// no floor holder and WebRTC has no relay frames, so that one cell had nothing.
	assert.equal(needsVoiceMeter('webrtc', 'FULL_DUPLEX'), true);
	// ...and nothing else, which is the load-bearing half. On WebRTC in a PTT mode the highlight is STICKY
	// (onFloorStatus sets it until the floor moves) while a meter drives markSpeaking, which arms a silence timer —
	// so running the meter there would let the first pause clear the holder's row with nothing to re-light it.
	['MULTI_CHANNEL_PTT', 'GLOBAL_PTT'].forEach(mode =>
			assert.equal(needsVoiceMeter('webrtc', mode), false, `webrtc/${mode} is driven by the floor`));
	// The relay transport has its own per-frame drivers in every mode.
	['FULL_DUPLEX', 'MULTI_CHANNEL_PTT', 'GLOBAL_PTT'].forEach(mode =>
			assert.equal(needsVoiceMeter('relay', mode), false, `relay/${mode} is driven by frames`));
});

test('isVoiceActive separates speech from room tone, at either scale', () => {
	// One rule for both transports and both sample formats: `scale` normalises to [-1, 1] — 32768 for captured
	// Int16, 1 for a decoded relay lane or an AnalyserNode's time-domain data. Two transports that disagreed about
	// what counts as talking would highlight the same person differently depending on how their audio arrived.
	assert.ok(isVoiceActive(new Float32Array(128).fill(0.5), 1), 'a loud float buffer is speech');
	assert.ok(!isVoiceActive(new Float32Array(128).fill(0.001), 1), 'room tone is not');
	assert.ok(isVoiceActive(new Int16Array(128).fill(16000), 32768), 'the same level as Int16 agrees');
	assert.ok(!isVoiceActive(new Int16Array(128).fill(32), 32768), '...and so does the quiet case');
});

test('isVoiceActive is false for an empty buffer rather than NaN', () => {
	// Math.sqrt(0/0) is NaN, and NaN > threshold is false — but only by accident, so pin it: the meter polls on a
	// timer and can sample before any audio has arrived.
	assert.equal(isVoiceActive(new Float32Array(0), 1), false);
});

test('the threshold sits between room tone and speech', () => {
	// Pinned so a future tweak is a deliberate decision rather than a drifting constant.
	assert.ok(VAD_RMS_THRESHOLD > 0.001 && VAD_RMS_THRESHOLD < 0.1, VAD_RMS_THRESHOLD);
	const atThreshold = new Float32Array(64).fill(VAD_RMS_THRESHOLD);
	assert.ok(!isVoiceActive(atThreshold, 1), 'exactly at the threshold is not yet speech (strict >)');
});

// --- the floor queue as a roster section (queueView) ------------------------------------------------
//
// The queue is a SECTION of the roster: a queued member's row moves into it rather than being repeated there, so
// there is nothing to truncate and no position field — order is the position, and the numbers people read come from
// a CSS counter over document order. What is left to pin is the gate (three terms), the order, and `isOffered`.

const QUEUE_VIEW = {selfId: SELF, holderId: HOLDER, waiting: [], floorQueueEnabled: true, mode: 'MULTI_CHANNEL_PTT'};

test('queueView: the queue comes back in order, with us marked', () => {
	const view = queueView({...QUEUE_VIEW, waiting: [OTHER, SELF]});
	assert.equal(view.shown, true);
	assert.equal(view.size, 2);
	// ORDER is the position — there is deliberately no position field to disagree with the CSS counter that draws
	// the numbers, so what this pins is that `entries` is the queue in FIFO order, unaltered.
	assert.deepEqual(view.entries.map(entry => entry.memberId), [OTHER, SELF]);
	assert.deepEqual(view.entries.map(entry => entry.isSelf), [false, true]);
});

test('queueView: order and length survive a long queue — nothing truncates or re-sorts', () => {
	// A queued member's row MOVES into the section rather than being repeated below it, so the section is as long as
	// the queue and the two lists together are never longer than the roster alone. A reversal or a re-sort here
	// would silently renumber everyone, since the numbers people read come from document order.
	const waiting = Array.from({length: 30}, (_, i) => `m-${i}`);
	const view = queueView({...QUEUE_VIEW, waiting});
	assert.equal(view.size, 30);
	assert.equal(view.entries.length, 30, 'no cut');
	assert.deepEqual(view.entries.map(entry => entry.memberId), waiting);
});

test('queueView: nothing is drawn when the owner has the queue switched off', () => {
	// The FLAG is the gate, not the contents: the server drains the queue on a disable, and the FloorQueueChanged
	// that says so lands before the emptied snapshot, so a non-empty list under an off flag is a stale in-flight
	// state that must not be drawn.
	const view = queueView({...QUEUE_VIEW, floorQueueEnabled: false, waiting: [OTHER, SELF]});
	assert.equal(view.shown, false);
	assert.deepEqual(view.entries, [], 'so every row belongs in the plain roster');
	assert.equal(view.size, 0);
});

test('queueView: full-duplex draws nothing even with the flag on and people listed', () => {
	// There is no floor in full-duplex, so there is nothing to be in line for. Mode is a gate term in its own
	// right; without it a channel switched to full-duplex would keep rendering the queue it can no longer use.
	const view = queueView({...QUEUE_VIEW, mode: 'FULL_DUPLEX', waiting: [OTHER, SELF]});
	assert.equal(view.shown, false);
	assert.deepEqual(view.entries, []);
});

test('queueView: an empty queue is hidden rather than drawn as an empty box', () => {
	assert.equal(queueView(QUEUE_VIEW).shown, false);
	assert.equal(queueView({...QUEUE_VIEW, holderId: null}).shown, false, 'a free floor with nobody waiting too');
});

test('queueView: the head of a FREE floor is marked as offered — and nobody else ever is', () => {
	const free = queueView({...QUEUE_VIEW, holderId: null, waiting: [OTHER, SELF]});
	assert.deepEqual(free.entries.map(entry => entry.isOffered), [true, false]);
	const busy = queueView({...QUEUE_VIEW, waiting: [OTHER, SELF]});
	assert.deepEqual(busy.entries.map(entry => entry.isOffered), [false, false],
			'a queue behind a live holder has no claim window running');
});

test('queueView: a snapshot that omits the holder reads as a free floor, not one held by undefined', () => {
	// The same loose `== null` floorStateFor documents. Tightening it to === would leave the head unmarked.
	const view = queueView({...QUEUE_VIEW, holderId: undefined, waiting: [OTHER]});
	assert.equal(view.entries[0].isOffered, true);
});

test('queueView: the offered mark agrees with floorStateFor for our own id', () => {
	// The anti-drift test. isOffered and FLOOR_MY_TURN are the same rule stated in two places — one for drawing
	// someone else's row, one for driving our own button — so they must never disagree about US.
	const cases = [
		{holderId: null, waiting: [SELF, OTHER]},
		{holderId: null, waiting: [OTHER, SELF]},
		{holderId: HOLDER, waiting: [SELF]},
		{holderId: null, waiting: [SELF]},
	];
	cases.forEach(snapshot => {
		const mine = queueView({...QUEUE_VIEW, ...snapshot}).entries.find(entry => entry.isSelf);
		const myTurn = floorStateFor(SELF, snapshot.holderId, snapshot.waiting) === FLOOR_MY_TURN;
		assert.equal(mine.isOffered, myTurn, JSON.stringify(snapshot));
	});
});




test('floorNarration: the two lines the panel duplicates fall silent while it is on screen', () => {
	// The point of the panel. With five in line, every arrival and departure renumbered everyone and wrote a line
	// per member, burying the things only the log can report (a rotation, a rename, a mute).
	const shown = {...IDLE_VIEW, floorQueueEnabled: true, mode: 'MULTI_CHANNEL_PTT'};
	assert.equal(floorNarration({...shown, holderId: HOLDER, waiting: [OTHER, SELF]}).kind, 'silent', 'in-line');
	assert.equal(floorNarration({...shown, waiting: [OTHER]}).kind, 'silent', 'offered');
});

test('floorNarration: with no panel to read, both lines are said as before', () => {
	// The queue flag off is the ordinary case, and the Java console client — which has no panel at all — depends on
	// this branch staying intact.
	assert.equal(floorNarration({...IDLE_VIEW, holderId: HOLDER, waiting: [OTHER, SELF]}).kind, 'in-line');
	assert.equal(floorNarration({...IDLE_VIEW, waiting: [OTHER]}).kind, 'offered');
	// ...and in full-duplex, where the panel is gated off by mode rather than by the flag.
	const duplex = {...IDLE_VIEW, floorQueueEnabled: true, mode: 'FULL_DUPLEX'};
	assert.equal(floorNarration({...duplex, waiting: [OTHER]}).kind, 'offered');
});

test('floorNarration: a silenced offer does not fall through to "Floor is free"', () => {
	// The floor is RESERVED for the head, not free. Returning the wrong kind here would be worse than saying
	// nothing, which is why the silenced branch returns explicitly instead of dropping out of the chain.
	const shown = {...IDLE_VIEW, floorQueueEnabled: true, mode: 'MULTI_CHANNEL_PTT', waiting: [OTHER]};
	assert.equal(floorNarration(shown).kind, 'silent');
	assert.notEqual(floorNarration(shown).kind, 'free', 'the one wrong thing to say about a reserved floor');
});

test('floorNarration: silencing the queue lines leaves every other kind intact', () => {
	// A blanket "say nothing while the panel is up" would have swallowed these — they are not in the panel.
	//
	// The fixture must genuinely put the panel UP: an earlier version of this test left `waiting` empty, so every
	// assertion ran with shown === false and passed without exercising the suppression at all. `waiting` therefore
	// holds a THIRD party — not us, so we are not IN_LINE, and the panel is up because somebody is queued.
	const shown = {...IDLE_VIEW, floorQueueEnabled: true, mode: 'MULTI_CHANNEL_PTT', waiting: [OTHER]};
	assert.equal(queueView(shown).shown, true, 'guard: the fixture really does put the panel up');
	assert.equal(floorNarration({...shown, holderId: HOLDER}).kind, 'talking',
			'who is talking is not something the queue panel shows');
	assert.equal(floorNarration({...shown, released: true}).kind, 'released');
	assert.equal(floorNarration({...shown, awaitingClaim: true}).kind, 'turn-passed');
	// 'free' cannot coexist with a non-empty queue — an unheld floor with somebody queued is RESERVED for the head
	// — so it is asserted with the panel down, which is the only state it occurs in.
	assert.equal(floorNarration({...IDLE_VIEW, floorQueueEnabled: true, mode: 'MULTI_CHANNEL_PTT'}).kind, 'free');
});

test('floorNarration: a suppressed snapshot says nothing WITHOUT forgetting what was last said', () => {
	// The distinction 'silent' exists for. `null` tells the caller to clear its last-logged key; 'silent' must not.
	// Conflating them reprinted the unchanged "Talking: X" on every raise/lower cycle — the exact noise the panel
	// was added to remove.
	const shown = {...IDLE_VIEW, floorQueueEnabled: true, mode: 'MULTI_CHANNEL_PTT'};
	const inLine = floorNarration({...shown, holderId: HOLDER, waiting: [OTHER, SELF]});
	assert.equal(inLine.kind, 'silent');
	assert.equal(inLine.key, undefined, 'a silent answer carries no key, so a caller cannot mistake it for a situation');
	assert.equal(floorNarration({...shown, waiting: [OTHER]}).kind, 'silent', 'and the offered line likewise');
	// LIVE and MY_TURN stay null — they are announced by FloorGranted/FloorReserved, and after them the next IDLE
	// snapshot SHOULD speak, which is what clearing the memory buys.
	assert.equal(floorNarration({...shown, holderId: SELF}), null, 'LIVE is still a hard null');
	assert.equal(floorNarration({...shown, waiting: [SELF]}), null, 'MY_TURN too');
});

test('floorNarration: the silent answer is a shared frozen value, so it cannot be mutated by a caller', () => {
	const shown = {...IDLE_VIEW, floorQueueEnabled: true, mode: 'MULTI_CHANNEL_PTT'};
	assert.equal(floorNarration({...shown, waiting: [OTHER]}), SILENT, 'the exported constant, not a fresh object');
	assert.equal(Object.isFrozen(SILENT), true);
});
