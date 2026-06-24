package io.github.ashr123.walkietalkie.shared.protocol;

/**
 * The conversation semantics of a channel. Fixed at the moment the channel is created;
 * later joiners must use the same mode.
 */
public enum ChannelMode {

	/**
	 * Named rooms; half-duplex push-to-talk with server-arbitrated floor control.
	 */
	MULTI_CHANNEL_PTT,

	/**
	 * A single shared room (always named `"global"`); half-duplex push-to-talk.
	 */
	GLOBAL_PTT,

	/**
	 * Everyone in the channel may transmit at once; no floor control.
	 */
	FULL_DUPLEX
}
