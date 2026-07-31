// Turns a microphone-capture failure into something the person in front of the browser can act on. A DOM-free module
// — a sibling of e2ee.js, talk.js and channel-flags.js — so it is unit-testable under Node's built-in test runner
// (`node --test`, no npm deps) from src/test/js/mic-errors.test.js.
//
// Why it exists: app.js used to log `getUserMedia`'s raw DOMException message, and the one people actually hit reads
// "The request is not allowed by the user agent or the platform in the current context." That is true, and it tells
// the reader nothing about what to do — it does not even distinguish "you tapped Block" from "this browser was never
// going to ask". The message below names both remedies for that case, because the one that catches people out is the
// second: a link opened inside a chat app's in-app browser is refused with no prompt at all.

/**
 * The advice for each `DOMException.name` getUserMedia rejects with. Deliberately phrased as what to DO, not as what
 * went wrong — the name is already in the console for anyone debugging.
 */
const CAPTURE_FAILURES = {
	// Permission refused: by the user, by a previously remembered "Block", by the OS not granting the browser app mic
	// access, or by an embedding context (an in-app browser / a frame with no `allow="microphone"`) that never asks.
	NotAllowedError: 'Microphone blocked — allow it for this page in your browser\'s site settings, or open the link '
		+ 'directly in Safari/Chrome rather than inside a chat app (an in-app browser often refuses without asking), '
		+ 'then reconnect',
	// No capture device at all.
	NotFoundError: 'No microphone found — connect or enable one, then reconnect',
	// The device exists but could not be opened: held exclusively by another app, or an OS/driver failure.
	NotReadableError: 'The microphone could not be opened — another app may be using it. Close it, then reconnect',
	// No device can satisfy the requested constraints. Ours are narrow in exactly one place: hi-fi asks for 2 channels
	// (see captureConstraints), so that is the setting to try turning off.
	OverconstrainedError: 'No microphone matches the requested settings — try turning off "High fidelity", '
		+ 'then reconnect',
	// Capture disabled by policy for this document.
	SecurityError: 'Microphone capture is disabled for this page by browser policy',
	// A catch-all the spec allows for "something else went wrong".
	AbortError: 'The microphone could not be started — reconnect to try again'
};

/**
 * Legacy Chrome/WebKit names, mapped to their modern equivalent. Worth carrying rather than dropping: this client is
 * used from phones, and the old names survive longest in exactly the embedded WebViews most likely to refuse capture.
 */
const LEGACY_NAMES = {
	PermissionDeniedError: 'NotAllowedError',
	DevicesNotFoundError: 'NotFoundError',
	TrackStartError: 'NotReadableError',
	ConstraintNotSatisfiedError: 'OverconstrainedError'
};

/**
 * What to tell the user when the microphone cannot be opened: `name` is the rejection's `DOMException.name` and
 * `rawMessage` its `message`, which is kept verbatim for anything unrecognised so no detail is ever lost.
 */
export function micErrorMessage(name, rawMessage) {
	return CAPTURE_FAILURES[LEGACY_NAMES[name] ?? name] ?? `Microphone unavailable: ${rawMessage}`;
}

/**
 * Why `navigator.mediaDevices` can be missing entirely, which is NOT a rejection — the property is simply absent, so
 * calling it throws a bare TypeError about reading a property of undefined and buries the actual cause.
 *
 * The cause is almost always the origin: capture is restricted to secure contexts, and this app is reachable over
 * plain HTTP (`walkie.tls.enabled=false`, the mode used behind a TLS-terminating proxy or tunnel). Point a browser at
 * `http://<lan-ip>:8080` directly and there is no microphone to be had, however correct everything else is.
 */
export const NO_CAPTURE_API_MESSAGE = 'This page cannot use a microphone: audio capture needs a secure context. '
	+ 'Open it over https:// (or via localhost) and reconnect';
