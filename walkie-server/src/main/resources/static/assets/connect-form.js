// Whether the Connect form is ready to send, as one pure function. A DOM-free module — a sibling of e2ee.js,
// talk.js, channel-flags.js, mic-errors.js and names.js — so it is unit-testable under Node's built-in test
// runner (`node --test`, no npm deps) from src/test/js/connect-form.test.js.
//
// Why this is a module and not three `if`s in the click handler: the same rules have to drive TWO different
// surfaces. The button's disabled state and the per-field messages are recomputed on every keystroke, while
// connect() and applyOrSwitch() need the same verdict at the moment they act. Those used to be separate
// code — the handler validated on click and logged a sentence — which is how they drift: a rule tightened in one
// place and not the other either blocks a legal form or lets an illegal one through to the server. One function,
// two callers, and a test that does not need a browser.
//
// The rules mirror the server's, which is the authority: ConnectionService rejects a bad display name
// (INVALID_DISPLAY_NAME), a bad channel name (INVALID_CHANNEL), and now a channel other than `global` that
// arrives with no key-check (PASSPHRASE_REQUIRED). Checking them here does not make the server's checks
// redundant — a client cannot be trusted — it just means the user is told before a round trip.

import {canonicalDisplayName, DISPLAY_NAME} from './names.js';

/**
 * The channel-name rule, mirroring the server's `CHANNEL_NAME` in ConnectionService and the Java client's copy.
 * ASCII-only and deliberately NOT the display-name rule: a channel name is the E2EE key-derivation salt
 * (§7 of the protocol doc), a routing key for channel-affinity ingress, and a map key, so it stays narrow.
 * No `.`, unlike display names.
 */
export const CHANNEL_NAME = /^[A-Za-z0-9_-]{1,64}$/;

/**
 * The global room is the server-managed broadcast channel: its name is forced to `global` and it is the one
 * channel that is never end-to-end encrypted, so neither a channel name nor a passphrase is asked of the user.
 * Every other mode requires both.
 */
export const GLOBAL_MODE = 'GLOBAL_PTT';

/**
 * Whether a field is simply not filled in yet, or holds something that cannot work. Worth distinguishing because
 * the summary line above the button would otherwise lie: telling someone who typed `my team` that a channel name is
 * "missing" sends them looking for an empty box. It also matches how the two cases actually differ in likelihood —
 * on a freshly loaded page everything is ABSENT, which is not a mistake, while INVALID means the user tried.
 */
export const ABSENT = 'absent';
export const INVALID = 'invalid';

/** Field ids, so a caller can map a problem onto the input it belongs to without matching on prose. */
export const DISPLAY_FIELD = 'display';
export const CHANNEL_FIELD = 'channel';
export const PASSPHRASE_FIELD = 'passphrase';

/**
 * What is wrong with the form, in the order the fields appear, as `{field, message}` objects — empty when it is
 * ready to send. Each message says what is needed rather than what is wrong ("Enter a channel name…" rather than
 * "Invalid channel"), because the form is INCOMPLETE far more often than it is mistaken: a freshly loaded page
 * now has every text field empty, so the common case is a user who has not finished typing, not one who typed
 * something illegal.
 *
 * Pure: everything it needs is an argument, including `secureContext`, which in the browser is
 * `window.isSecureContext && !!window.crypto?.subtle`. That is what makes it testable under Node, and it is also
 * the honest shape — whether a key can be derived at all is an input to whether this form can be submitted, not
 * something the rules can discover for themselves.
 *
 * @param form {{displayName: string, channel: string, mode: string, passphrase: string, secureContext: boolean}}
 * @returns {{field: string, message: string}[]}
 */
export function connectProblems(form) {
	const problems = [];
	const display = canonicalDisplayName(form.displayName ?? '');
	if (display === '') {
		problems.push({field: DISPLAY_FIELD, kind: ABSENT, message: 'Enter a display name.'});
	} else if (!DISPLAY_NAME.test(display)) {
		problems.push({
			field: DISPLAY_FIELD,
			kind: INVALID,
			message: '1-32 letters, digits or spaces in any language, plus _ . or - — no invisible characters.',
		});
	}

	// Global forces the channel name server-side and refuses a passphrase outright (ENCRYPTION_NOT_ALLOWED), so
	// asking for either would be asking for something that cannot be used. Both fields are hidden in that mode.
	if (form.mode !== GLOBAL_MODE) {
		const channel = (form.channel ?? '').trim();
		if (channel === '') {
			problems.push({field: CHANNEL_FIELD, kind: ABSENT, message: 'Enter a channel name.'});
		} else if (!CHANNEL_NAME.test(channel)) {
			problems.push({
				field: CHANNEL_FIELD,
				kind: INVALID,
				message: '1-64 letters, digits, _ or - — no spaces or dots.',
			});
		}

		if ((form.passphrase ?? '') === '') {
			problems.push({
				field: PASSPHRASE_FIELD,
				kind: ABSENT,
				message: 'Enter the channel’s encryption passphrase — every channel except the global room is '
						+ 'end-to-end encrypted.',
			});
		} else if (!form.secureContext) {
			// Reported against the passphrase field because that is the input the user cannot make work here, but
			// the fix is the page's URL, not the value. Only reachable once a passphrase is required, which is why
			// it is worth stating so plainly: there is no longer a "connect without encryption" way out of it.
			problems.push({
				field: PASSPHRASE_FIELD,
				// INVALID rather than ABSENT: a passphrase WAS typed, it just cannot be used from this page.
				kind: INVALID,
				message: 'Encryption needs a secure context — open this page over HTTPS (or on localhost). '
						+ 'Encryption cannot be turned off.',
			});
		}
	}
	return problems;
}

/** Whether the form can be sent — the single gate for both the Connect button and the connect/switch handlers. */
export function canConnect(form) {
	return connectProblems(form).length === 0;
}

/**
 * One line naming what is still outstanding, for the button's tooltip and the status line above it: "Missing:
 * display name, passphrase", "Check: channel name", or both joined when the form is some of each. Derived from the
 * same problem list, so it can never disagree with the per-field messages, and ordered by field so it does not
 * reshuffle as the user types.
 *
 * The two kinds are worded separately because one summary for both was measured lying in the browser: a channel of
 * `my team` reported "Missing: channel name", sending the reader to look for an empty box.
 *
 * No de-duplication, because [#connectProblems] reports **at most one problem per field** — every rule above is an
 * `else if` on the field before it, since the second rule for a field is only meaningful once the first has passed
 * (there is no point telling someone their empty passphrase also needs HTTPS). connect-form.test.js asserts that
 * invariant directly; if a future rule breaks it, that test fails rather than this line silently repeating a field.
 */
export function readinessSummary(form) {
	const labels = {[DISPLAY_FIELD]: 'display name', [CHANNEL_FIELD]: 'channel name', [PASSPHRASE_FIELD]: 'passphrase'};
	const problems = connectProblems(form);
	const named = kind => problems.filter(problem => problem.kind === kind).map(problem => labels[problem.field]);
	const absent = named(ABSENT);
	const invalid = named(INVALID);
	return [
		absent.length > 0 ? `Missing: ${absent.join(', ')}` : '',
		invalid.length > 0 ? `Check: ${invalid.join(', ')}` : '',
	].filter(Boolean).join(' · ');
}
