// Relay-path end-to-end encryption for the browser client, plus the outbound transmit-gate decision. Pulled out
// of app.js into a DOM-free module so it can be unit-tested under Node's built-in test runner (`node --test`),
// which exposes the same Web Crypto API (`globalThis.crypto`) the browser does. MUST stay byte-identical to the
// Java client's FrameCrypto and to FrameCryptoTest's known-answer vectors.

export const E2EE_SCHEME = 0xe2;        // wire marker for an encrypted frame: [scheme][IV(12)][ciphertext+tag]; kept outside the codec-tag set {1,2} so a plaintext receiver drops it cleanly
export const E2EE_AAD = Uint8Array.of(E2EE_SCHEME);   // the scheme byte, authenticated (GCM additionalData) but not encrypted, so the envelope is covered by the tag

const IV_BYTES = 12;
const KEY_BYTES = 32;          // AES-256
const KCV_BYTES = 16;          // key-check value, derived alongside the key
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
		{name: 'AES-GCM', iv, tagLength: 128, additionalData: E2EE_AAD}, key, plaintext));
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
	return frame.length < 1 + IV_BYTES + 16 || frame[0] !== E2EE_SCHEME ?
		Promise.reject(new Error('not an end-to-end-encrypted frame')) :
		crypto.subtle.decrypt(
			{name: 'AES-GCM', iv: frame.subarray(1, 1 + IV_BYTES), tagLength: 128, additionalData: E2EE_AAD},
			key, frame.subarray(1 + IV_BYTES)
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
 * announced key-check. Returns 'plaintext' (unencrypted channel — send as-is), 'encrypt' (our key matches the
 * channel's announced one — send ciphertext), or 'drop' (the channel announces encryption but our key-check
 * doesn't match — stay SILENT). 'drop' covers BOTH a member with no key (the plaintext→encrypted enable, never
 * leak plaintext) AND a member holding a STALE key after a rotation it hasn't adopted (don't emit undecodable
 * audio; a straggler is muted until it adopts). Mirrors the Java client's WalkieClient.outboundFrame.
 */
export function frameDisposition(heldKeyCheck, channelKeyCheck) {
	return channelKeyCheck == null ? 'plaintext' : heldKeyCheck === channelKeyCheck ? 'encrypt' : 'drop';
}

/**
 * The pure decision for an announced passphrase change, given the channel's announced key-check and the
 * key-check the client derived from the passphrase it holds (or null if none was derived). 'disable' = the owner
 * turned encryption off (drop the key); 'apply' = the derived key matches, adopt it; 'keep' = hold the current
 * key (we don't have the new passphrase yet, or it mismatched — never adopt a non-matching key). The caller
 * must pass the LIVE announced key-check (re-read AFTER any await) so two rapid rotations can't apply a key that
 * only matched a stale value. Mirrors the Java client's WalkieClient.rekeyAction.
 */
export function rekeyAction(announcedKeyCheck, derivedKeyCheck) {
	return announcedKeyCheck == null ?
		'disable' :
		derivedKeyCheck != null && derivedKeyCheck === announcedKeyCheck ?
			'apply' :
			'keep';
}
