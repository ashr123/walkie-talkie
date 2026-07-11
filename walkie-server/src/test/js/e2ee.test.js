// Browser-client tests for the relay E2EE module, run under Node's built-in test runner (`node --test`) — the
// same Web Crypto API the browser uses (`globalThis.crypto`), no npm dependencies. Wired into the Gradle build
// via the `jsTest` task (guarded so it skips when Node isn't on PATH).
//
// The known-answer vectors below are IDENTICAL to walkie-client-java's FrameCryptoTest, so these assertions and
// that JUnit test together pin the browser and Java clients to byte-for-byte interoperable E2EE.

import {test} from 'node:test';
import assert from 'node:assert/strict';

import {
	E2EE_SCHEME,
	deriveKey,
	deriveKeyBytesHex,
	encryptFrame,
	encryptFrameWithIv,
	decryptFrame,
	frameDisposition,
	rekeyAction,
	wrapPassphrase,
	unwrapPassphrase,
} from '../../main/resources/static/assets/e2ee.js';

/**
 * Fixed inputs shared with FrameCryptoTest (Java). If the browser's PBKDF2/AES-GCM ever drifts from the Java
 * client, one side's known-answer assertions fail.
 */
const PASSPHRASE = 'correct horse battery staple';
const CHANNEL = 'lobby';
const IV = Uint8Array.of(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11);
const PLAINTEXT = Uint8Array.of(1, 16, 32, 48, 64);

const EXPECTED_KEY_HEX = '43321a28736472e94ff819ef9364476d5324b8fa550115409047f7da41fcbc06';
const EXPECTED_KCV = 'c9ea045aeadb2254fff7fa0efeb4d18a';
const EXPECTED_CIPHERTEXT_HEX = '64d66fb60c1fe48c515bb15362b5bcd63cca8d0a48';

const hex = bytes => [...bytes].map(b => Number(b).toString(16).padStart(2, '0')).join('');

test('derives the same AES key as the Java client (PBKDF2-HMAC-SHA512, 600k iters)', async () => {
	assert.equal(await deriveKeyBytesHex(PASSPHRASE, CHANNEL), EXPECTED_KEY_HEX);
});

test('derives the same key-check value as the Java client', async () => {
	const {keyCheck} = await deriveKey(PASSPHRASE, CHANNEL);
	assert.equal(keyCheck, EXPECTED_KCV);
});

test('encrypts to the same ciphertext as the Java client for a fixed key/IV/plaintext', async () => {
	const {key} = await deriveKey(PASSPHRASE, CHANNEL);
	const frame = await encryptFrameWithIv(PLAINTEXT, key, IV);
	// frame = [scheme(1)][IV(12)][ciphertext+tag]; the Java KAT pins the ciphertext+tag tail.
	assert.equal(frame[0], E2EE_SCHEME);
	assert.deepEqual(frame.subarray(1, 13), IV);
	assert.equal(hex(frame.subarray(13)), EXPECTED_CIPHERTEXT_HEX);
});

test('encrypt → decrypt round-trips', async () => {
	const {key} = await deriveKey(PASSPHRASE, CHANNEL);
	const frame = await encryptFrame(PLAINTEXT, key);
	const recovered = new Uint8Array(await decryptFrame(frame, key));
	assert.deepEqual(recovered, PLAINTEXT);
});

test('decrypt rejects a frame encrypted under a different passphrase', async () => {
	const {key} = await deriveKey(PASSPHRASE, CHANNEL);
	const frame = await encryptFrame(PLAINTEXT, key);
	const {key: wrongKey} = await deriveKey('a different passphrase', CHANNEL);
	await assert.rejects(() => decryptFrame(frame, wrongKey));
});

test('decrypt rejects a plaintext peer frame (no scheme byte), even with the right key', async () => {
	const {key} = await deriveKey(PASSPHRASE, CHANNEL);
	const frame = await encryptFrame(PLAINTEXT, key);
	frame[0] = 1;   // CODEC_OPUS tag — a plaintext Opus frame, not the 0xE2 scheme byte
	await assert.rejects(() => decryptFrame(frame, key));
});

// --- the transmit-gate invariant: only put on the wire what the channel's current key-check matches ------

test('a genuinely unencrypted channel sends in the clear', () => {
	assert.equal(frameDisposition(null, null), 'plaintext');
});

test('ENABLE transition: a member with no key into an announced-encrypted channel is DROPPED', () => {
	// The leak case the round-1 review caught: no key held (channel was plaintext) but the owner just announced a
	// non-null key-check. Must NOT fall back to plaintext.
	assert.equal(frameDisposition(null, 'non-null-kcv'), 'drop');
});

test('frameDisposition: a matching held key-check encrypts; a stale one is muted', () => {
	assert.equal(frameDisposition('kcv-X', 'kcv-X'), 'encrypt');     // our key matches the channel -> send ciphertext
	assert.equal(frameDisposition('kcv-OLD', 'kcv-NEW'), 'drop');    // stale key after a rotation we haven't adopted -> muted
	assert.equal(frameDisposition('kcv-X', null), 'plaintext');      // channel is unencrypted
});

test('wrapPassphrase round-trips under the same key, and only that key can unwrap', async () => {
	// Owner-initiated auto-distribution: the new passphrase is wrapped under the OLD key; a member holding it
	// recovers it (and one who doesn't, can't). Cross-checked against the Java FrameCrypto.wrap/unwrap.
	const {key: oldKey} = await deriveKey('old-secret', 'team');
	const wrapped = await wrapPassphrase('the-new-passphrase', oldKey);
	assert.equal(await unwrapPassphrase(wrapped, oldKey), 'the-new-passphrase');
	const {key: otherKey} = await deriveKey('a-different-old-secret', 'team');
	await assert.rejects(() => unwrapPassphrase(wrapped, otherKey), 'only the wrapping key can unwrap');
});

test('the AES key + key-check are salted per channel (switching rooms re-keys)', async () => {
	// A regression to passphrase-only salting would still pass the single-channel KATs above yet silently reuse
	// one key across every room — and must stay in lock-step with the Java FrameCryptoTest.derivesADifferentKeyPerChannel.
	const a = await deriveKey('same-pass', 'room-a');
	const b = await deriveKey('same-pass', 'room-b');
	assert.notEqual(a.keyCheck, b.keyCheck, 'different channels must derive different key-checks');
	assert.notEqual(
		await deriveKeyBytesHex('same-pass', 'room-a'),
		await deriveKeyBytesHex('same-pass', 'room-b'),
		'different channels must derive different AES keys');
});

test('decryptFrame rejects a frame shorter than scheme+IV+tag (29 bytes)', async () => {
	const {key} = await deriveKey(PASSPHRASE, CHANNEL);
	await assert.rejects(
		() => decryptFrame(Uint8Array.of(E2EE_SCHEME, 1, 2, 3), key),
		'a runt frame must be rejected before reaching crypto.subtle.decrypt');
});

// --- rekeyAction: the announced-passphrase-change decision (must stay in lock-step with WalkieClient.rekeyAction)

test('rekeyAction: disable when nothing is announced', () => {
	assert.equal(rekeyAction(null, 'whatever'), 'disable');
	assert.equal(rekeyAction(null, null), 'disable');
});

test('rekeyAction: apply only on a matching derived key-check', () => {
	assert.equal(rekeyAction('kcv-X', 'kcv-X'), 'apply');
});

test('rekeyAction: keep on a mismatch or no derived key (never adopt a wrong key)', () => {
	assert.equal(rekeyAction('kcv-X', 'kcv-Y'), 'keep');
	assert.equal(rekeyAction('kcv-X', null), 'keep');
});
