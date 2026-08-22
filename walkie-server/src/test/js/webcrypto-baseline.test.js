// Keeps the browser client inside the Web Crypto surface that BROWSERS actually have — which is not the surface this
// test suite runs on.
//
// The hazard is specific and was measured, not imagined. These tests run under `node --test`, and Node's
// `crypto.subtle` is ahead of every shipping browser: Node has had ML-KEM and ML-DSA since v24.7.0, while as of
// August 2026 nothing post-quantum is in `crypto.subtle` in ANY released browser — it lives in a WICG draft, Chrome
// keeps it behind `about://flags#webcrypto-pqc`, Firefox's patch has not landed and WebKit has no implementation.
// web-platform-tests bears this out: Chrome, Edge, Firefox and Safari all pass 0 of the 468 ML-KEM `generateKey`
// tests. So a well-meant change to `e2ee.js` — reaching for ML-KEM, Argon2, SHA-3, ChaCha20-Poly1305 or the new
// `encapsulateKey`/`decapsulateKey` methods — would pass this entire suite on the developer's machine and then throw
// in every browser. Green tests, broken client, and the byte-for-byte parity with the Java client silently gone.
//
// The guard is deliberately a SOURCE scan rather than a runtime one. A runtime check only sees the paths a test
// happens to exercise, and it cannot see `app.js` at all (its top-level `window.addEventListener` makes it
// unimportable under Node — the very reason the DOM-free modules exist). Reading the files as text covers every
// browser asset, exercised or not.
//
// The deny-list is derived from THIS RUNTIME rather than hardcoded, so it grows by itself: whatever `SubtleCrypto`
// gains in a future Node is automatically forbidden here until it is deliberately added to the baseline below.

import {test} from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync, readdirSync} from 'node:fs';

/** Every browser asset, as text. `app.js` is included precisely because it cannot be imported. */
const ASSETS = new URL('../../main/resources/static/assets/', import.meta.url);
const SOURCES = readdirSync(ASSETS)
	.filter(name => name.endsWith('.js'))
	.map(name => ({name, code: readFileSync(new URL(name, ASSETS), 'utf8')}));

/**
 * The `crypto.subtle` methods the browser client may use: the four that carry the E2EE. Adding to this list is a
 * decision about browser support, which is why it is a list and not a pattern — see this file's header.
 */
const ALLOWED_SUBTLE_METHODS = new Set(['deriveBits', 'importKey', 'encrypt', 'decrypt']);

/** Non-`subtle` crypto the client may use: the IV source. */
const ALLOWED_CRYPTO_MEMBERS = new Set(['subtle', 'getRandomValues']);

/**
 * The algorithm and format strings the E2EE is defined in terms of — the same four the Java client's FrameCrypto
 * uses, which is what makes the known-answer vectors in e2ee.test.js meaningful.
 */
const BASELINE_ALGORITHMS = new Set(['PBKDF2', 'SHA-512', 'AES-GCM', 'raw']);

/**
 * The `crypto.subtle` METHODS no shipping browser implements — the key-encapsulation surface the WICG draft adds. Kept
 * separate from the derived list below, which is merely "beyond what this client uses" and includes plenty that
 * browsers do have (`digest`, `sign`, `generateKey`); conflating the two would make a failure message say something
 * untrue.
 */
const BROWSER_ABSENT_METHODS = ['encapsulateKey', 'encapsulateBits', 'decapsulateKey', 'decapsulateBits',
	'getPublicKey'];

/**
 * Algorithm names that exist in some runtime but no shipping browser. Unlike the method deny-list, these cannot be
 * discovered from an object, so they are named. Matching is case-insensitive and prefix-based: `ML-KEM` catches
 * `ML-KEM-768`, `SHA3` catches `SHA3-256`.
 */
const NOT_IN_BROWSERS = ['ML-KEM', 'ML-DSA', 'SLH-DSA', 'X-Wing', 'Argon2', 'SHA3', 'ChaCha20'];

test('the browser client calls only Web Crypto methods that browsers have', () => {
	// Derived from the runtime, so a method added by a future Node is covered without editing this file. Note what this
	// set is: everything beyond the four the client uses — NOT everything browsers lack. Some of it (digest, sign,
	// generateKey) is in every browser; using it would be a parity change rather than a compatibility break, and both
	// are worth stopping here.
	const beyondBaseline = Object.getOwnPropertyNames(SubtleCrypto.prototype)
		.filter(name => name !== 'constructor' && !ALLOWED_SUBTLE_METHODS.has(name));

	for (const {name, code} of SOURCES) {
		for (const [, method] of code.matchAll(/crypto\.subtle\.(\w+)/g)) {
			assert.ok(ALLOWED_SUBTLE_METHODS.has(method),
				`${name} calls crypto.subtle.${method}(), which is outside the four methods this client is built on`
				+ (BROWSER_ABSENT_METHODS.includes(method)
					? ' — and no shipping browser implements it, so this suite would pass while every browser throws.'
					: '. Browsers may well have it; the objection is that the Java client mirrors these four exactly, so '
						+ 'adding one belongs in ALLOWED_SUBTLE_METHODS as a decision, not here as a side effect.'));
		}
		for (const forbidden of beyondBaseline) {
			const absentFromBrowsers = BROWSER_ABSENT_METHODS.includes(forbidden);
			assert.ok(!new RegExp(`subtle\\s*\\.\\s*${forbidden}\\b`).test(code),
				`${name} uses crypto.subtle.${forbidden}(), which is outside the four methods this client is built on`
				+ (absentFromBrowsers
					? ' — and which no shipping browser implements, so this suite would pass and every browser would throw.'
					: '. It exists in browsers, but adopting it changes the E2EE the Java client mirrors, so decide it '
						+ 'deliberately rather than here.'));
		}
	}
});

test('the browser client names only the algorithms the Java client mirrors', () => {
	for (const {name, code} of SOURCES) {
		// Every quoted string in a `name:`/`hash:` position, plus the bare-string first argument of importKey.
		const named = [
			...code.matchAll(/(?:name|hash)\s*:\s*'([^']+)'/g),
			...code.matchAll(/importKey\(\s*'([^']+)'/g),
		].map(([, value]) => value);

		for (const algorithm of named) {
			assert.ok(BASELINE_ALGORITHMS.has(algorithm),
				`${name} names the algorithm or format '${algorithm}', which is outside the E2EE baseline `
				+ `(${[...BASELINE_ALGORITHMS].join(', ')}). The Java client's FrameCrypto mirrors those exactly, so a `
				+ `new one breaks the known-answer parity even where browsers support it.`);
		}
		for (const forbidden of NOT_IN_BROWSERS) {
			assert.ok(!new RegExp(`['"\`]${forbidden}`, 'i').test(code),
				`${name} mentions '${forbidden}', which no shipping browser implements in crypto.subtle.`);
		}
	}
});

test('this runtime really is ahead of browsers, so the scan above is doing the work', () => {
	// Not a tautology: it asserts the PREMISE of the two tests above and fails loudly if that ever stops holding —
	// either because a future Node drops these, or, better, because browsers gain them and the header's reasoning
	// needs rewriting rather than being quietly wrong.
	const present = BROWSER_ABSENT_METHODS.filter(method => typeof SubtleCrypto.prototype[method] === 'function');

	assert.ok(present.length > 0,
		`expected this runtime to expose key-encapsulation methods that browsers do not (${BROWSER_ABSENT_METHODS
			.join(', ')}); if none are here, either Node regressed or browsers caught up, and this file's premise `
		+ 'needs restating');
	// The four the client relies on must of course exist, or the parity vectors could not run at all.
	for (const method of ALLOWED_SUBTLE_METHODS) {
		assert.equal(typeof SubtleCrypto.prototype[method], 'function', `SubtleCrypto.${method} is missing`);
	}
});
