// Browser-client tests for the Connect form's readiness rules, run under Node's built-in test runner
// (`node --test`), no npm dependencies. Wired into the Gradle build by the `jsTest` task.
//
// The rules exist so the Connect button can be disabled until the form is legal AND so connect()/applyOrSwitch()
// can gate on exactly the same verdict. That is the property most worth pinning: the two callers must never be
// able to disagree, and the field a problem is attributed to must be the field the user has to fix.
//
// The channel-name and display-name vectors are deliberately the same ones the server's ConnectionServiceTest and
// the Java client's WalkieClientTest use, for the reason names.test.js and e2ee.test.js do it: the rule lives in
// three copies because the browser cannot share code with the JVM, so the only thing keeping them honest is that
// all three are pinned against one list.

import {test} from 'node:test';
import assert from 'node:assert/strict';

import {
	ABSENT,
	canConnect,
	CHANNEL_FIELD,
	CHANNEL_NAME,
	connectProblems,
	DISPLAY_FIELD,
	GLOBAL_MODE,
	INVALID,
	PASSPHRASE_FIELD,
	readinessSummary,
} from '../../main/resources/static/assets/connect-form.js';

/** A form that is ready to send, so each test can express exactly one thing being wrong with it. */
function validForm(overrides = {}) {
	return {
		displayName: 'Roy Ash',
		channel: 'team-1',
		mode: 'MULTI_CHANNEL_PTT',
		passphrase: 'correct horse battery staple',
		secureContext: true,
		...overrides,
	};
}

const fieldsOf = form => connectProblems(form).map(problem => problem.field);

test('a complete form is ready to send', () => {
	assert.deepEqual(connectProblems(validForm()), []);
	assert.ok(canConnect(validForm()));
	assert.equal(readinessSummary(validForm()), '');
});

test('a freshly loaded page — every field empty — names all three, in field order', () => {
	// The state the page now starts in, since the display and channel defaults were removed. This is the message
	// the user sees first, so its ORDER matters: it has to read top-to-bottom like the form.
	const empty = validForm({displayName: '', channel: '', passphrase: ''});
	assert.deepEqual(fieldsOf(empty), [DISPLAY_FIELD, CHANNEL_FIELD, PASSPHRASE_FIELD]);
	assert.equal(readinessSummary(empty), 'Missing: display name, channel name, passphrase');
	assert.ok(!canConnect(empty));
});

test('each required field is required on its own', () => {
	[
		[{displayName: ''}, DISPLAY_FIELD],
		[{channel: ''}, CHANNEL_FIELD],
		[{passphrase: ''}, PASSPHRASE_FIELD],
	].forEach(([override, field]) => {
		assert.deepEqual(fieldsOf(validForm(override)), [field], JSON.stringify(override));
		assert.ok(!canConnect(validForm(override)));
	});
});

test('a whitespace-only entry reads as EMPTY, not as an illegal name', () => {
	// canonicalDisplayName trims, and the channel rule is applied to the trimmed value, so neither can be
	// satisfied by pressing space. Asserting the MESSAGE, not just the field: without the trim the value would
	// still be rejected (spaces are not in either pattern) but by the format rule, so the user would be told
	// their spaces are the wrong SHAPE rather than that the field is blank. A mutant that drops the trim
	// survives a field-only assertion.
	assert.deepEqual(connectProblems(validForm({displayName: '   '})),
			[{field: DISPLAY_FIELD, kind: ABSENT, message: 'Enter a display name.'}]);
	assert.deepEqual(connectProblems(validForm({channel: '   '})),
			[{field: CHANNEL_FIELD, kind: ABSENT, message: 'Enter a channel name.'}]);
});

test('an empty field asks for a value; the format rule stays quiet', () => {
	// The empty case has to be its OWN branch: '' also fails both patterns, so a mutant deleting the empty check
	// still reports the right field — with the wrong message. Pin the message for every required field.
	assert.deepEqual(connectProblems(validForm({displayName: ''})),
			[{field: DISPLAY_FIELD, kind: ABSENT, message: 'Enter a display name.'}]);
	assert.deepEqual(connectProblems(validForm({channel: ''})),
			[{field: CHANNEL_FIELD, kind: ABSENT, message: 'Enter a channel name.'}]);
	assert.match(connectProblems(validForm({passphrase: ''}))[0].message, /^Enter the channel/);
});

test('at most one problem per field — the invariant readinessSummary relies on', () => {
	// readinessSummary joins the fields verbatim, so a field reported twice would read "display name, display
	// name". Every rule is therefore an `else if` on the field before it. Exhaustive over the ways each field can
	// be wrong, in both modes.
	[
		{displayName: ''}, {displayName: '   '}, {displayName: 'x'.repeat(33)}, {displayName: 'bad\u200bname'},
		{channel: ''}, {channel: '   '}, {channel: 'my team'}, {channel: 'x'.repeat(65)},
		{passphrase: ''}, {passphrase: 'secret', secureContext: false}, {passphrase: '', secureContext: false},
		{displayName: '', channel: '', passphrase: '', secureContext: false},
	].forEach(override => {
		['MULTI_CHANNEL_PTT', GLOBAL_MODE].forEach(mode => {
			const fields = fieldsOf(validForm({...override, mode}));
			assert.equal(new Set(fields).size, fields.length,
					`repeated field for ${JSON.stringify({...override, mode})}: ${fields}`);
		});
	});
});

test('an illegal value is reported differently from an absent one', () => {
	// Two distinct messages on purpose: "enter something" for the common unfinished case, and the format rule only
	// when what was typed cannot work. Telling a user who typed 'my team' to "enter a channel name" would be a lie.
	const absent = connectProblems(validForm({channel: ''}))[0].message;
	const illegal = connectProblems(validForm({channel: 'my team'}))[0].message;
	assert.notEqual(absent, illegal);
	assert.match(illegal, /no spaces/);
});

test('the channel rule is the server\'s: ASCII, no spaces, no dots, 1-64', () => {
	['a', 'team-1', 'A_b-9', 'x'.repeat(64)].forEach(name =>
			assert.ok(CHANNEL_NAME.test(name), `should accept ${name}`));
	['', 'x'.repeat(65), 'my team', 'team.one', 'שלום', 'a/b'].forEach(name =>
			assert.ok(!CHANNEL_NAME.test(name), `should reject ${name}`));
});

test('a display name in any script is accepted, matching names.js', () => {
	['יוסי כהן', '李雷', 'José', 'Ελένη'].forEach(name =>
			assert.deepEqual(connectProblems(validForm({displayName: name})), [], name));
});

test('a non-empty but illegal display name is reported, with the format rule', () => {
	// The gap a mutation run found: every other test either uses a VALID name or an EMPTY one, and empty is caught
	// by its own branch — so deleting the format rule entirely changed nothing observable. These are the names
	// names.js exists to reject: over 32 code points, and one carrying an invisible character (ZWSP), which is
	// excluded because a control or bidi character can make a roster row read differently than it is.
	[['x'.repeat(33), 'too long'], ['bad​name', 'zero-width space'], ['Roy‮Ash', 'bidi override']]
			.forEach(([name, why]) => {
				const problems = connectProblems(validForm({displayName: name}));
				assert.equal(problems.length, 1, `${why} should be reported`);
				assert.equal(problems[0].field, DISPLAY_FIELD, why);
				assert.match(problems[0].message, /1-32 letters/, why);
			});
});

test('the global room asks for neither a channel name nor a passphrase', () => {
	// It is the one unencrypted channel and the server forces its name, so demanding either would be demanding
	// something that cannot be used — the server would answer ENCRYPTION_NOT_ALLOWED for a passphrase.
	const global = validForm({mode: GLOBAL_MODE, channel: '', passphrase: ''});
	assert.deepEqual(connectProblems(global), []);
	assert.ok(canConnect(global));
});

test('the global room does not need a secure context either', () => {
	// Nothing is derived there, so a plain-HTTP page can still use it. This is the ONLY way to connect without
	// HTTPS now that a passphrase is mandatory everywhere else.
	assert.ok(canConnect(validForm({mode: GLOBAL_MODE, channel: '', passphrase: '', secureContext: false})));
});

test('the global room still validates the display name', () => {
	// The one rule that is not about the channel: every join carries a name, global included.
	assert.deepEqual(fieldsOf(validForm({mode: GLOBAL_MODE, displayName: ''})), [DISPLAY_FIELD]);
});

test('an insecure context blocks a passphrase-bearing form, against the passphrase field', () => {
	// crypto.subtle is absent over plain HTTP to a LAN address, so no key can be derived. Reported against the
	// passphrase because that is the input that cannot work — even though the fix is the page's URL.
	const insecure = validForm({secureContext: false});
	assert.deepEqual(fieldsOf(insecure), [PASSPHRASE_FIELD]);
	assert.match(connectProblems(insecure)[0].message, /HTTPS/);
	assert.ok(!canConnect(insecure));
});

test('an insecure context is reported once, not twice, when the passphrase is also missing', () => {
	// Both rules touch the passphrase field; only the actionable one should speak. Telling someone to type a
	// passphrase AND that encryption needs HTTPS, in one breath, reads as two unrelated failures.
	const both = validForm({passphrase: '', secureContext: false});
	assert.equal(connectProblems(both).length, 1);
	assert.match(connectProblems(both)[0].message, /Enter the channel/);
});

test('readinessSummary names each outstanding field once, in form order', () => {
	assert.equal(readinessSummary(validForm({displayName: '', passphrase: ''})),
			'Missing: display name, passphrase');
	assert.equal(readinessSummary(validForm({channel: ''})), 'Missing: channel name');
});

test('readinessSummary says "Check" for a value that is wrong, not "Missing"', () => {
	// Found by driving the real form in a browser: `my team` reported "Missing: channel name", which sends the
	// reader hunting for an empty box. An unfinished field and a mistyped one are different situations and the
	// one line above the button is where the difference has to show.
	assert.equal(readinessSummary(validForm({channel: 'my team'})), 'Check: channel name');
	assert.equal(readinessSummary(validForm({displayName: 'x'.repeat(33)})), 'Check: display name');
	assert.equal(readinessSummary(validForm({secureContext: false})), 'Check: passphrase');
});

test('readinessSummary reports both kinds when the form is some of each', () => {
	assert.equal(readinessSummary(validForm({displayName: '', channel: 'my team'})),
			'Missing: display name · Check: channel name');
});

test('every problem carries a kind, and it matches whether the field was filled in', () => {
	// The kind is what the summary's wording keys off, so it has to be right for every rule — not just the two
	// the summary tests happen to exercise.
	[
		[{displayName: ''}, ABSENT], [{displayName: 'x'.repeat(33)}, INVALID],
		[{channel: ''}, ABSENT], [{channel: 'my team'}, INVALID],
		[{passphrase: ''}, ABSENT], [{secureContext: false}, INVALID],
	].forEach(([override, kind]) => {
		const problems = connectProblems(validForm(override));
		assert.equal(problems.length, 1, JSON.stringify(override));
		assert.equal(problems[0].kind, kind, JSON.stringify(override));
	});
});

test('absent properties are treated as empty, not as a crash', () => {
	// The DOM hands back '' for an empty input, but a caller assembling this object by hand (or a future field
	// that is not rendered in some mode) must not turn a missing key into a TypeError inside the rules.
	assert.deepEqual(fieldsOf({mode: 'MULTI_CHANNEL_PTT', secureContext: true}),
			[DISPLAY_FIELD, CHANNEL_FIELD, PASSPHRASE_FIELD]);
});

test('canConnect agrees with connectProblems for every combination of the three fields', () => {
	// The invariant the two callers depend on: the button's gate and the handlers' gate are the same verdict.
	// Exhaustive over present/absent for each field plus both modes, so no combination can drift.
	['MULTI_CHANNEL_PTT', GLOBAL_MODE].forEach(mode =>
			['', 'Roy'].forEach(displayName =>
					['', 'team'].forEach(channel =>
							['', 'secret'].forEach(passphrase => {
								const form = {displayName, channel, mode, passphrase, secureContext: true};
								assert.equal(canConnect(form), connectProblems(form).length === 0,
										JSON.stringify(form));
							}))));
});
