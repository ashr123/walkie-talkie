// The channel's standing owner-toggleable flags, described ONCE each. Pulled out of app.js into a DOM-free module —
// a sibling of e2ee.js and talk.js — so the render decision is unit-testable under Node's built-in test runner
// (`node --test`, no npm deps) from src/test/js/channel-flags.test.js. app.js builds the badge row and the owner's
// checkbox rows FROM this table, so a fourth flag is an entry here rather than markup in two places plus three
// render sites that can disagree.
//
// Every flag is server state: `field` names the client field that ONLY a server message writes (the `Joined`
// snapshot and that flag's own `…Changed` broadcast), never the click. That is the whole point of `flagDisplay`
// taking an explicit snapshot — there is no input by which a just-clicked checkbox could colour what is shown, so a
// toggle the server refuses cannot leave the UI claiming something untrue.

/**
 * In display order. `field` is the client state field (and the `Joined` component) the server owns; `badge` is what
 * every member sees when it is on, `label` is the owner's checkbox; `command` builds the owner's request — note the
 * wire field differs per message (`setLocked` carries `locked`, the others `enabled`), which is exactly the sort of
 * detail worth pinning in a test; `applies` (optional) rules a flag out where it is meaningless, and `warn` marks
 * the one that keeps people out rather than merely describing the room.
 */
export const CHANNEL_FLAGS = [
	{
		field: 'locked',
		badge: '🔒 Locked',
		badgeTitle: 'The owner locked this channel — a newcomer has to be admitted',
		warn: true,
		label: 'Locked — a newcomer has to be admitted',
		command(enabled) {
			return {type: 'setLocked', locked: enabled};
		}
	},
	{
		field: 'floorQueueEnabled',
		badge: '✋ Queue on',
		badgeTitle: 'A busy floor forms a line — tap Talk to join it',
		label: 'Raise-hand queue for a busy floor',
		// Full-duplex has no talk floor, so a queue means nothing there — and the server refuses the toggle with
		// INVALID_MODE. Worth ruling out rather than just hiding the checkbox: the flag can still read true, left
		// over from a push-to-talk channel this session switched away from.
		applies(view) {
			return view.mode !== 'FULL_DUPLEX';
		},
		command(enabled) {
			return {type: 'setFloorQueue', enabled};
		}
	},
	{
		field: 'muteNewMembers',
		badge: '🔇 Joiners muted',
		badgeTitle: 'The owner mutes every member that joins',
		label: 'Mute members as they join',
		command(enabled) {
			return {type: 'setMuteNewMembers', enabled};
		}
	}
];

/**
 * How one flag renders for a snapshot of channel state:
 *   applies    — is the flag meaningful in this channel at all (drives the owner's row);
 *   badgeShown — should every member see its badge;
 *   checked    — what the owner's checkbox reads.
 *
 * `view` needs only `mode` plus each flag's own field. A field that is missing or not exactly `true` reads as OFF,
 * so a snapshot from a server that has not sent a flag yet renders as absent rather than as truthy.
 */
export function flagDisplay(flag, view) {
	const applies = flag.applies === undefined || flag.applies(view);
	const on = view[flag.field] === true;
	return {applies, badgeShown: applies && on, checked: on};
}
