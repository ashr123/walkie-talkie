// Browser-client tests for the microphone-failure advice, run under Node's built-in test runner (`node --test`), no
// npm dependencies. Wired into the Gradle build by the `jsTest` task, which picks up a new *.test.js with no build
// change.
//
// What these pin is that a capture failure tells the user what to DO. The motivating report was a phone that logged
// "Connect error: The request is not allowed by the user agent or the platform in the current context." three times —
// accurate, unactionable, and in that case not even the obvious cause (the link had been opened inside a chat app's
// in-app browser, which refuses capture without ever prompting).

import {test} from 'node:test';
import assert from 'node:assert/strict';

import {micErrorMessage, NO_CAPTURE_API_MESSAGE} from '../../main/resources/static/assets/mic-errors.js';

const RAW = 'The request is not allowed by the user agent or the platform in the current context.';

/** The names getUserMedia can reject with that we have advice for. */
const KNOWN = [
	'NotAllowedError',
	'NotFoundError',
	'NotReadableError',
	'OverconstrainedError',
	'SecurityError',
	'AbortError'
];

test('a recognised failure replaces the raw text rather than decorating it', () => {
	// The whole point: the DOMException message is what nobody could act on, so it must not survive into the log line.
	KNOWN.forEach(name => {
		const message = micErrorMessage(name, RAW);
		assert.ok(message.length > 0, name);
		assert.ok(!message.includes(RAW), `${name} still quotes the raw message`);
	});
});

test('an unrecognised failure keeps the raw text, so no detail is lost', () => {
	// A name we have no advice for must not be swallowed — better an ugly message than a silent one. Covers a future
	// spec addition and a browser inventing its own name.
	const message = micErrorMessage('SomeFutureError', RAW);
	assert.ok(message.includes(RAW));
	assert.match(message, /Microphone unavailable/);
});

test('a blocked microphone names BOTH remedies', () => {
	// The case that actually bit, and it has two causes people confuse: a remembered "Block" for the site, and an
	// embedding context that never asks at all. Advice covering only the first sends someone into settings that
	// already say Allow — so the in-app-browser escape hatch is pinned here explicitly.
	const message = micErrorMessage('NotAllowedError', RAW);
	assert.match(message, /site settings/i);
	assert.match(message, /Safari\/Chrome|in-app browser/i);
});

test('unsatisfiable constraints point at the one setting we constrain', () => {
	// captureConstraints only asks for something a device can refuse in hi-fi mode, where it requests 2 channels.
	// Naming that setting is the difference between a dead end and a fix the user can apply.
	assert.match(micErrorMessage('OverconstrainedError', RAW), /High fidelity/);
});

test('a device held by another app is distinguished from one that is missing', () => {
	// Same remedy family, different action: close the other app vs plug something in. Collapsing them would send
	// people looking for hardware they already have.
	assert.notEqual(micErrorMessage('NotReadableError', RAW), micErrorMessage('NotFoundError', RAW));
	assert.match(micErrorMessage('NotReadableError', RAW), /another app/i);
	assert.match(micErrorMessage('NotFoundError', RAW), /No microphone found/i);
});

test('legacy Chrome/WebKit names get their modern equivalent\'s advice', () => {
	// Carried on purpose: these old names survive longest in the embedded WebViews most likely to refuse capture,
	// which is exactly the population this advice is for. Each must be identical to its modern twin, not merely
	// non-empty — an alias pointing at the wrong entry would give confidently wrong instructions.
	assert.equal(micErrorMessage('PermissionDeniedError', RAW), micErrorMessage('NotAllowedError', RAW));
	assert.equal(micErrorMessage('DevicesNotFoundError', RAW), micErrorMessage('NotFoundError', RAW));
	assert.equal(micErrorMessage('TrackStartError', RAW), micErrorMessage('NotReadableError', RAW));
	assert.equal(micErrorMessage('ConstraintNotSatisfiedError', RAW), micErrorMessage('OverconstrainedError', RAW));
});

test('every recognised failure gives distinct advice', () => {
	// Two names sharing a message means one of them is being answered with the other's remedy.
	assert.equal(new Set(KNOWN.map(name => micErrorMessage(name, RAW))).size, KNOWN.length);
});

test('the missing-API message explains the secure context, not the TypeError', () => {
	// `navigator.mediaDevices` being absent is a property, not a rejection: without this the user sees a TypeError
	// about reading a property of undefined. The real cause is the origin — this server can serve plain HTTP
	// (walkie.tls.enabled=false), and http://<lan-ip>:8080 has no microphone available however correct everything
	// else is.
	assert.match(NO_CAPTURE_API_MESSAGE, /https:\/\/|secure context/);
	assert.ok(!NO_CAPTURE_API_MESSAGE.toLowerCase().includes('typeerror'));
});
