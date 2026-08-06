// The name rules — display names AND channel names — as pure functions. A DOM-free module, a sibling of e2ee.js,
// talk.js, channel-flags.js, mic-errors.js and connect-form.js, so it is unit-testable under Node's built-in test
// runner (`node --test`, no npm deps) from src/test/js/names.test.js.
//
// Both rules MUST agree, character for character, with the server's copies in ConnectionService and with the Java
// client's in WalkieClient. Three copies exist because the browser cannot share code with the JVM; names.test.js
// pins the agreement with the same vectors the Java tests use, the way e2ee.test.js pins the crypto known-answer
// vectors against FrameCryptoTest.
//
// The channel name is the stricter of the two, and canonicalising it is not cosmetic the way it is for a display
// name: it is the PBKDF2 SALT (see e2ee.js), so two members whose channel names differ by one byte derive
// DIFFERENT KEYS and sit in the same room unable to hear each other, with a PASSPHRASE_MISMATCH neither can
// explain. Measured: `שׁלום` typed with the precomposed presentation form U+FB2A and the same name as
// U+05E9 U+05C1 produce different AES keys before NFC and identical ones after.

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

/**
 * The channel-name rule: letters, combining marks and digits from ANY script, a plain space, plus `_` and `-`,
 * 1 to 64 code points. Mirrors the server's `CHANNEL_NAME` in ConnectionService and the Java client's copy.
 *
 * The space is U+0020 and ONLY U+0020 — not `\p{Zs}`. That is the important part of this rule, not a detail:
 * NBSP, the ideographic space and the thin spaces all render IDENTICALLY to a plain space, so allowing them
 * would make `my room` typed with a space and `my room` typed with an NBSP two different rooms with two
 * different keys (the name is the PBKDF2 salt) that no user could tell apart. That is the same class of failure
 * NFC normalisation exists to prevent here, except invisible rather than merely obscure. Being an ALLOW-list is
 * what buys that for free, along with excluding every format and control character (`\p{C}` — ZWSP, ZWNJ, soft
 * hyphen, the bidi overrides). It matters more for a channel name than for a display name, because a channel
 * name is a rendezvous key with no `#id` printed beside it: a name carrying something unprintable is a room
 * nobody else can retype.
 *
 * Still no `.`, unchanged from when this was ASCII-only. Nothing depends on it and nothing wants it.
 */
export const CHANNEL_NAME = /^[\p{L}\p{M}\p{N} _-]{1,64}$/u;

/**
 * The canonical form of a channel name: NFC-composed, internal whitespace runs collapsed to one plain space, then
 * trimmed. **This is the form that must reach the wire AND the key derivation**, because the channel name is the
 * PBKDF2 salt — see this module's header for the measured two-keys-one-room failure it prevents. The server
 * canonicalises again on receipt (it cannot trust a client to have done it), so both sides agree on the map key.
 *
 * Collapsing runs is a DEVIATION from [#canonicalDisplayName], which deliberately leaves `Roy  Ash` as typed, and
 * the difference is the job each name does. A display name is a label beside an id; two members whose names differ
 * by an invisible double space are still told apart by the `#id` every client prints. A channel name IS the
 * rendezvous — get one space wrong and you are alone in a room that looks right, holding a key nobody else
 * derives. So the goal here is convergence: every spelling that LOOKS the same must reduce to the same string.
 * The collapsed set is written out as `[\p{Zs}\t\n\r\v\f]` rather than `\s`, and that is a cross-platform
 * requirement, not pedantry: JavaScript's `\s` matches NBSP and the other Unicode spaces, while **Java's `\s`
 * does not** (it is ASCII-only unless UNICODE_CHARACTER_CLASS is set). Using `\s` on both sides would therefore
 * have the browser collapse an NBSP to a space — making the name valid — while the server and the Java client
 * rejected the very same name, which is the two-clients-disagree failure this whole rule exists to avoid.
 * `\p{Zs}` is a Unicode category with identical membership in both languages, and the five ASCII control
 * whitespace characters are unambiguous, so the two implementations provably agree. Everything outside that set
 * that merely looks blank — ZWSP (`\p{Cf}`), the line/paragraph separators (`\p{Zl}`/`\p{Zp}`) — is left alone
 * and then rejected by the allow-list, on both sides, which is the right answer for a character nobody can retype.
 *
 * Collapsing an NBSP rather than rejecting it is deliberate and is the better half of the bargain: the user who
 * pasted one gets the room everybody else is in, instead of a validation error about a character they cannot see.
 *
 * Trimmed last, for the same reason display names are: a leading or trailing space off a copy-paste is not a
 * different room.
 */
export function canonicalChannelName(raw) {
	return raw.normalize('NFC').replace(/[\p{Zs}\t\n\r\v\f]+/gu, ' ').trim();
}

/** Whether `raw` is an acceptable channel name once canonicalised. */
export function isValidChannelName(raw) {
	return CHANNEL_NAME.test(canonicalChannelName(raw));
}
