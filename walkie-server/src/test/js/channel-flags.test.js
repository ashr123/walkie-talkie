// Browser-client tests for the channel-flag table and its render decision, run under Node's built-in test runner
// (`node --test`), no npm dependencies. Wired into the Gradle build via the `jsTest` task.
//
// What these pin is that the owner's standing flags are rendered from SERVER state and nothing else. app.js builds
// both the everyone-visible badge row and the owner's checkboxes from this one table, and asks flagDisplay what each
// should show; the click handler only sends a command. So if the derivation cannot see a click, a toggle the server
// refuses cannot leave the UI claiming something untrue — that property is what the last test states directly.

import {test} from 'node:test';
import assert from 'node:assert/strict';

import {CHANNEL_FLAGS, flagDisplay} from '../../main/resources/static/assets/channel-flags.js';

const flag = field => CHANNEL_FLAGS.find(f => f.field === field);

/** A confirmed snapshot: every flag off, in a push-to-talk channel (where all three apply). */
function view(overrides) {
	return {mode: 'MULTI_CHANNEL_PTT', locked: false, floorQueueEnabled: false, muteNewMembers: false, ...overrides};
}

test('the flags are exactly the ones the server sends in Joined', () => {
	// The coupling worth pinning: each `field` names BOTH the client field and the component of the Joined snapshot
	// the server fills it from. Rename one end without the other and the badge silently never appears, because
	// `view[field]` reads undefined — which flagDisplay treats as off rather than throwing.
	assert.deepEqual(CHANNEL_FLAGS.map(f => f.field), ['locked', 'floorQueueEnabled', 'muteNewMembers']);
});

test('a badge shows only when the server says the flag is on', () => {
	CHANNEL_FLAGS.forEach(f => {
		assert.equal(flagDisplay(f, view({[f.field]: true})).badgeShown, true, f.field);
		assert.equal(flagDisplay(f, view({[f.field]: false})).badgeShown, false, f.field);
	});
});

test('the owner\'s tick mirrors the confirmed field, never anything else', () => {
	CHANNEL_FLAGS.forEach(f => {
		assert.equal(flagDisplay(f, view({[f.field]: true})).checked, true, f.field);
		assert.equal(flagDisplay(f, view({[f.field]: false})).checked, false, f.field);
	});
});

test('a flag the snapshot does not mention reads as off', () => {
	// A server that has not sent a flag yet — or an older one that never will — must render as absent rather than
	// throwing or showing.
	CHANNEL_FLAGS.forEach(f => {
		const missing = view({});
		delete missing[f.field];
		assert.equal(flagDisplay(f, missing).badgeShown, false, f.field);
		assert.equal(flagDisplay(f, missing).checked, false, f.field);
	});
});

test('only the boolean true counts as on, so a truthy non-boolean cannot show a badge', () => {
	// Why the derivation tests `=== true` rather than truthiness: the string "false" is TRUTHY in JavaScript, so a
	// flag that arrived as text — a hand-rolled client, a proxy that stringifies JSON, a future field typed loosely —
	// would light a badge that says the exact opposite. Falsy non-booleans are covered too, for symmetry.
	CHANNEL_FLAGS.forEach(f => {
		['false', 'true', 1, 0, '', 'yes'].forEach(value => {
			const display = flagDisplay(f, view({[f.field]: value}));
			assert.equal(display.badgeShown, false, `${f.field} = ${JSON.stringify(value)}`);
			assert.equal(display.checked, false, `${f.field} = ${JSON.stringify(value)}`);
		});
	});
});

test('the raise-hand queue does not apply in full-duplex, even if the flag is still on', () => {
	// Reachable: the flag survives a switch from a push-to-talk channel, so it can read true in a channel that has no
	// floor at all. Both the badge and the owner's row must drop out — the server refuses the toggle there with
	// INVALID_MODE, so offering it would be a lie in the other direction.
	const inDuplex = flagDisplay(flag('floorQueueEnabled'), view({mode: 'FULL_DUPLEX', floorQueueEnabled: true}));
	assert.equal(inDuplex.applies, false);
	assert.equal(inDuplex.badgeShown, false);

	const inPtt = flagDisplay(flag('floorQueueEnabled'), view({floorQueueEnabled: true}));
	assert.equal(inPtt.applies, true);
	assert.equal(inPtt.badgeShown, true);
});

test('the other two flags apply in every mode', () => {
	// Mute matters MOST in full-duplex, where every mic is open, and a lock is about joining rather than talking.
	['locked', 'muteNewMembers'].forEach(field => {
		['MULTI_CHANNEL_PTT', 'GLOBAL_PTT', 'FULL_DUPLEX'].forEach(mode => {
			assert.equal(flagDisplay(flag(field), view({mode})).applies, true, `${field} in ${mode}`);
		});
	});
});

test('each flag builds the right request, with the right wire field', () => {
	// Easy to get wrong and invisible until an owner clicks: setLocked carries `locked`, the other two carry
	// `enabled`. A mismatched field deserializes as false server-side, so the toggle would appear to do nothing.
	assert.deepEqual(flag('locked').command(true), {type: 'setLocked', locked: true});
	assert.deepEqual(flag('locked').command(false), {type: 'setLocked', locked: false});
	assert.deepEqual(flag('floorQueueEnabled').command(true), {type: 'setFloorQueue', enabled: true});
	assert.deepEqual(flag('muteNewMembers').command(true), {type: 'setMuteNewMembers', enabled: true});
});

test('every flag is fully described, so a generated control is never blank', () => {
	// app.js writes these straight into the DOM it builds; a missing one would render an empty badge or an unlabelled
	// checkbox rather than failing loudly.
	CHANNEL_FLAGS.forEach(f => {
		assert.ok(f.badge.length > 0, `${f.field}: badge`);
		assert.ok(f.badgeTitle.length > 0, `${f.field}: badgeTitle`);
		assert.ok(f.label.length > 0, `${f.field}: label`);
		assert.equal(typeof f.command, 'function', `${f.field}: command`);
	});
	assert.equal(new Set(CHANNEL_FLAGS.map(f => f.badge)).size, CHANNEL_FLAGS.length, 'badges are distinguishable');
});

test('nothing but confirmed state can reach the display — a pending click is invisible to it', () => {
	// THE property. The click handler in app.js only sends a command; the render reads this derivation, and this
	// derivation is a pure function of the snapshot the server wrote. Stated here as: decorating the snapshot with
	// anything a click might plausibly leave behind changes nothing at all.
	CHANNEL_FLAGS.forEach(f => {
		const confirmed = view({[f.field]: false});
		const withPendingClick = {
			...confirmed,
			pending: {[f.field]: true},
			[`pending_${f.field}`]: true,
			[`${f.field}Requested`]: true,
			lastClicked: f.field
		};
		assert.deepEqual(flagDisplay(f, withPendingClick), flagDisplay(f, confirmed), f.field);
		assert.equal(flagDisplay(f, withPendingClick).badgeShown, false, `${f.field}: a click alone shows nothing`);
	});
});
