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
 * The channel-name rule: letters, combining marks and digits from ANY script, plus `_` and `-`, 1 to 64 code
 * points. Mirrors the server's `CHANNEL_NAME` in ConnectionService and the Java client's copy.
 *
 * Deliberately stricter than [DISPLAY_NAME] in two ways, and both are load-bearing rather than tidiness:
 *
 * - **No whitespace.** The Java console client's channel command is `c <channel> [mode] [key]`, split on `\s+`,
 *   so a room name containing a space would break that parse. A room name gains nothing from spaces anyway.
 * - **No `.`** — unchanged from when this was ASCII-only. Nothing depends on it, but nothing wants it either.
 *
 * As with a display name this is an ALLOW-list, so everything invisible is excluded for free: every separator
 * (`\p{Zs}`) and every format/control character (`\p{C}` — ZWSP, ZWNJ, soft hyphen, the bidi overrides) is
 * simply not in the class. That matters more here than for a display name: a channel name is a rendezvous key
 * with no `#id` beside it, so a name carrying an invisible character would be a room nobody else can retype.
 */
export const CHANNEL_NAME = /^[\p{L}\p{M}\p{N}_-]{1,64}$/u;

/**
 * The canonical form of a channel name: NFC-composed, then trimmed. **This is the form that must reach the wire
 * AND the key derivation**, because the channel name is the PBKDF2 salt — see this module's header for the
 * measured two-keys-one-room failure it prevents. The server canonicalises again on receipt (it cannot trust a
 * client to have done it), so both sides agree on the map key too.
 *
 * Trimmed for the same reason display names are: a trailing space off a copy-paste is not a different room. It
 * cannot be part of a valid name anyway, so trimming turns "this is invalid" into "this just works".
 */
export function canonicalChannelName(raw) {
	return raw.normalize('NFC').trim();
}

/** Whether `raw` is an acceptable channel name once canonicalised. */
export function isValidChannelName(raw) {
	return CHANNEL_NAME.test(canonicalChannelName(raw));
}
