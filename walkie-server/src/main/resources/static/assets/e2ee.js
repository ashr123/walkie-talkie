// Relay-path end-to-end encryption for the browser client, plus the outbound transmit-gate decision. Pulled out
// of app.js into a DOM-free module — talk.js and channel-flags.js are the others — so it can be unit-tested under
// Node's built-in test runner (`node --test`), which exposes the same Web Crypto API (`globalThis.crypto`) the
// browser does. MUST stay byte-identical to the Java client's FrameCrypto and to FrameCryptoTest's known-answer
// vectors.

export const E2EE_SCHEME = 0xe2;        // wire marker for an encrypted frame: [scheme][IV(12)][ciphertext+tag]; kept outside the codec-tag set {1,2} so a plaintext receiver drops it cleanly
export const E2EE_AAD = Uint8Array.of(E2EE_SCHEME);   // the scheme byte, authenticated (GCM additionalData) but not encrypted, so the envelope is covered by the tag

const IV_BYTES = 12;
const KEY_BYTES = 32;          // AES-256
const KCV_BYTES = 16;          // key-check value, derived alongside the key
const TAG_LENGTH_BITS = 128; // AES-GCM tag length, in bits (16 bytes)
const PBKDF2_ITERATIONS = 600000;
const SALT_PREFIX = 'walkie-talkie:e2ee:';

function hex(bytes) {
	return [...bytes].map(b => Number(b).toString(16).padStart(2, '0')).join('');
}

/**
 * The shared PBKDF2-HMAC-SHA512 derivation: 600000 iterations, salted on the channel name, 384 bits out — the
 * first 32 are the AES key, the next 16 are the key-check value. PBKDF2's first output block is length-
 * independent, so the 32-byte AES key is identical to a 256-bit derivation.
 */
async function deriveBits384(passphrase, effectiveChannel) {
	const enc = new TextEncoder();
	return new Uint8Array(await crypto.subtle.deriveBits(
		{
			name: 'PBKDF2',
			salt: enc.encode(SALT_PREFIX + effectiveChannel),
			iterations: PBKDF2_ITERATIONS,
			hash: 'SHA-512'
		},
		await crypto.subtle.importKey(
			'raw',
			enc.encode(passphrase),
			'PBKDF2',
			false,
			['deriveBits']
		),
		(KEY_BYTES + KCV_BYTES) * 8
	));
}

/**
 * Derive the per-channel material from the shared passphrase. Returns {key, keyCheck}: a non-extractable
 * AES-GCM CryptoKey and the hex key-check value the client sends in its join.
 */
export async function deriveKey(passphrase, effectiveChannel) {
	const bits = await deriveBits384(passphrase, effectiveChannel);
	return {
		key: await crypto.subtle.importKey(
			'raw',
			bits.slice(0, KEY_BYTES),
			'AES-GCM',
			false,
			['encrypt', 'decrypt']
		),
		keyCheck: hex(bits.slice(KEY_BYTES, KEY_BYTES + KCV_BYTES))
	};
}

/**
 * The raw 32-byte AES key, hex-encoded — exposed only so the known-answer test can pin it against the Java
 * client (the live client never extracts the key; deriveKey returns a non-extractable CryptoKey).
 */
export async function deriveKeyBytesHex(passphrase, effectiveChannel) {
	return hex((await deriveBits384(passphrase, effectiveChannel)).slice(0, KEY_BYTES));
}

/**
 * Wrap a plaintext frame as scheme(1) ‖ IV(12) ‖ ciphertext+tag(16). The scheme byte lets a receiver
 * distinguish an encrypted frame from a plaintext peer's [codec tag][payload] (which starts with 1 or 2).
 */
export async function encryptFrame(plaintext, key) {
	return encryptFrameWithIv(plaintext, key, crypto.getRandomValues(new Uint8Array(IV_BYTES)));
}

/**
 * As encryptFrame, but with a caller-supplied IV — used by the known-answer test to reproduce a fixed vector.
 */
export async function encryptFrameWithIv(plaintext, key, iv) {
	const ct = new Uint8Array(await crypto.subtle.encrypt(
		{
			name: 'AES-GCM',
			iv,
			tagLength: TAG_LENGTH_BITS,
			additionalData: E2EE_AAD
		},
		key,
		plaintext
	));
	const out = new Uint8Array(1 + iv.length + ct.length);
	out[0] = E2EE_SCHEME;
	out.set(iv, 1);
	out.set(ct, 1 + iv.length);
	return out;
}

/**
 * Recover the plaintext frame from scheme ‖ IV ‖ ciphertext+tag; reject a missing scheme byte (a plaintext peer
 * in an encrypted channel) or a bad tag (tampered / wrong passphrase) — never decoding ciphertext as audio.
 */
export function decryptFrame(frame, key) {
	return frame.length < 1 + IV_BYTES + TAG_LENGTH_BITS / 8 || frame[0] !== E2EE_SCHEME ?
		Promise.reject(new Error('not an end-to-end-encrypted frame')) :
		crypto.subtle.decrypt(
			{
				name: 'AES-GCM',
				iv: frame.subarray(1, 1 + IV_BYTES),
				tagLength: TAG_LENGTH_BITS,
				additionalData: E2EE_AAD
			},
			key,
			frame.subarray(1 + IV_BYTES)
		);
}

/**
 * Wrap a passphrase under `key` for an owner-initiated re-key: base64 of the frame envelope around the
 * passphrase's UTF-8 bytes (same crypto/format as an audio frame, so byte-compatible with the Java client's
 * FrameCrypto.wrap). A member that still holds the old key unwraps it to adopt the new passphrase automatically;
 * the server relays the blob without ever seeing the passphrase.
 */
export async function wrapPassphrase(passphrase, key) {
	return btoa(String.fromCharCode(...(await encryptFrame(new TextEncoder().encode(passphrase), key))));
}

/**
 * Inverse of wrapPassphrase: recover the passphrase from a base64 wrapped blob, decrypting with `key`. Rejects
 * if the blob was not wrapped under this key (a different/rotated key, or tampered) — caller then falls back to
 * a manual re-entry.
 */
export async function unwrapPassphrase(wrapped, key) {
	return new TextDecoder().decode(await decryptFrame(
		Uint8Array.from(atob(wrapped), c => c.charCodeAt(0)),
		key
	));
}

/**
 * The pure outbound transmit-gate decision, given the key-check of the key we currently HOLD and the channel's
 * announced key-check. Returns 'plaintext' (send as-is), 'encrypt' (our key matches the channel's announced one
 * — send ciphertext), or 'drop' (stay SILENT).
 *
 * `plaintextAllowed` is the whole point of this function's shape, and it is the CALLER's own knowledge — true only
 * for the server-managed `global` room, the one channel that is plaintext by design. It exists because the gate
 * used to infer "this channel is unencrypted" from `channelKeyCheck == null`, and that value comes from the
 * SERVER: a single forged `passphraseChanged { keyCheck: null }` therefore flipped a whole encrypted channel to
 * sending in the clear. Deciding on a fact the client owns instead means no value the server sends can produce a
 * plaintext frame in a named channel — the worst it can do is get us dropped, which is silence, not a leak.
 *
 * The `heldKeyCheck == null` term beside it closes the remaining trust: `plaintextAllowed` is derived from the
 * MODE, and the mode arrives in the `Joined` snapshot, so a server that lied about it could otherwise still ask a
 * member of a named channel to talk in the clear. But whether we hold a key is a fact the client owns outright —
 * we derived it from a passphrase the user typed — and holding one means encryption was intended, full stop. So
 * plaintext requires BOTH that the channel is allowed to be plaintext AND that we never derived a key for it.
 *
 * Outside that, the answer is 'encrypt' only when we hold a key whose key-check equals the announced one, and
 * 'drop' for every other combination. That deliberately includes BOTH nulls: a named channel with nothing
 * announced and nothing held used to be 'plaintext' and is now fail-closed.
 *
 * 'drop' has two ordinary triggers, neither of them an attack: a member holding a STALE key after a rotation it
 * has not adopted (don't emit undecodable audio — a straggler is muted until it adopts), and the round trip of
 * switching between channels, where the announced key-check briefly belongs to the channel we are leaving.
 * Mirrors the Java client's WalkieClient.outboundFrame.
 */
export function frameDisposition(heldKeyCheck, channelKeyCheck, plaintextAllowed) {
	if (plaintextAllowed && heldKeyCheck == null) {
		return 'plaintext';
	}
	return heldKeyCheck != null && heldKeyCheck === channelKeyCheck ? 'encrypt' : 'drop';
}

/**
 * The pure decision for an announced passphrase change, given the channel's announced key-check and the
 * key-check the client derived from the passphrase it holds (or null if none was derived). 'apply' = the derived
 * key matches, adopt it; 'keep' = hold the current key (we don't have the new passphrase yet, or it mismatched —
 * never adopt a non-matching key). The caller must pass the LIVE announced key-check (re-read AFTER any await) so
 * two rapid rotations can't apply a key that only matched a stale value.
 *
 * A null announced key-check yields 'keep', not a third 'disable' that dropped the key. It used to mean "the owner
 * turned encryption off"; no conformant server sends it now (a clearing rotation is refused with
 * PASSPHRASE_REQUIRED), and obeying it would have turned a feature into a DOWNGRADE — with the key dropped,
 * frameDisposition returns 'plaintext', so one forged or buggy broadcast would put a whole encrypted channel on
 * the air in the clear. Keeping the key we hold fails closed. Mirrors the Java client's WalkieClient.rekeyAction.
 */
export function rekeyAction(announcedKeyCheck, derivedKeyCheck) {
	return announcedKeyCheck != null && derivedKeyCheck != null && derivedKeyCheck === announcedKeyCheck ?
		'apply' :
		'keep';
}
