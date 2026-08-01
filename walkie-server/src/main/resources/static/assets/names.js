// The display-name rule, as one pure function pair. A DOM-free module — a sibling of e2ee.js, talk.js,
// channel-flags.js and mic-errors.js — so it is unit-testable under Node's built-in test runner (`node --test`,
// no npm deps) from src/test/js/names.test.js.
//
// It MUST agree, character for character, with the server's DISPLAY_NAME + canonicalDisplayName in
// ConnectionService and with the Java client's copy in WalkieClient. Three copies exist because the browser cannot
// share code with the JVM; names.test.js pins the agreement with the same vectors the Java tests use, the way
// e2ee.test.js pins the crypto known-answer vectors against FrameCryptoTest.

/**
 * Letters, combining marks and digits from ANY script — Hebrew, Han, accented Latin — plus a plain space, `_`, `.`
 * and `-`, between 1 and 32 code points.
 *
 * `\p{M}` is not optional: Hebrew niqqud and Arabic diacritics are combining marks, so leaving it out would
 * silently reject vocalised text. The `u` flag is what makes `\p{…}` legal AND makes the quantifier count code
 * points rather than UTF-16 units, so a name of 32 astral letters passes where counting units would see 64.
 *
 * Excluded is everything invisible: every other separator (`\p{Zs}` — NBSP, ideographic space, the thin spaces) and
 * every format or control character (`\p{C}` — ZWSP, ZWNJ, soft hyphen, the bidi overrides). Not to stop
 * impersonation — both clients always print the session id beside a name, so look-alike names are still told
 * apart — but because a control character can split a log record in two and a bidi override such as U+202E
 * reorders the text AROUND it, so a roster row could be made to read differently than it is.
 */
export const DISPLAY_NAME = /^[\p{L}\p{M}\p{N} _.\-]{1,32}$/u;

/**
 * The canonical form of a display name: NFC-composed, then trimmed. This is what the SERVER will store, broadcast
 * and compare, so the client has to produce the same string or it ends up arguing with itself — the Rename button
 * compares the typed value against the server-confirmed name, and would stay lit forever over a name the server
 * had already accepted in a slightly different form.
 *
 * NFC because one name can arrive as two byte sequences that render identically (`é` as one code point or as `e`
 * plus a combining acute; Hebrew with niqqud likewise). Trim because leading and trailing spaces carry no
 * information and the roster does not even render them — HTML collapses whitespace runs and drops the edges, so
 * `Roy Ash`, `Roy  Ash` and ` Roy Ash ` all came out the same pixel width when measured.
 *
 * Order matters: trimming happens AFTER normalising and BEFORE validation, because a name of nothing but spaces
 * satisfies the pattern's `{1,32}` on its own and has to be reduced to the empty string for the pattern to reject
 * it. Spaces INSIDE a name are deliberately left alone — `Roy  Ash` is kept as typed.
 */
export function canonicalDisplayName(raw) {
	return raw.normalize('NFC').trim();
}

/** Whether `raw` is an acceptable display name once canonicalised — the check both callers should use. */
export function isValidDisplayName(raw) {
	return DISPLAY_NAME.test(canonicalDisplayName(raw));
}
