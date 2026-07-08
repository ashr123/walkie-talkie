package io.github.ashr123.walkietalkie.shared.protocol;

import com.fasterxml.jackson.annotation.JsonEnumDefaultValue;

/// The machine-readable reason of a [ServerMessage.ErrorMessage] — the closed set a client may branch on
/// (the accompanying `message` is display-only and NOT part of the contract; the same code can carry
/// different texts). Serialized **as the constant name** (`NOT_OWNER`, …), like [ChannelMode] — no custom
/// wire names.
///
/// Forward compatibility: a client on an older protocol version can receive a code this enum doesn't know
/// yet. [#UNKNOWN] is the [JsonEnumDefaultValue] fallback for that case — a deserializer configured to read
/// unknown enum values as the default (the reference Java client enables
/// `DeserializationFeature.READ_UNKNOWN_ENUM_VALUES_USING_DEFAULT_VALUE`) degrades gracefully to "log it and
/// carry on" instead of failing the whole message.
public enum ErrorCode {
	/// A control frame could not be parsed (malformed JSON or an unknown `type`).
	BAD_MESSAGE,
	/// `join` with a channel name not matching the allowed pattern.
	INVALID_CHANNEL,
	/// `join` or `rename` with a display name not matching the allowed pattern.
	INVALID_DISPLAY_NAME,
	/// `changeMode` to a mode not applicable here (global push-to-talk outside the `global` channel).
	INVALID_MODE,
	/// `join` naming the reserved `global` channel with a non-global mode.
	RESERVED_CHANNEL,
	/// A global-push-to-talk `join` carrying a key-check — the global room is always unencrypted.
	ENCRYPTION_NOT_ALLOWED,
	/// An in-channel request (floor, mode, passphrase, transfer, mute, lock…) sent before joining a channel.
	NOT_IN_CHANNEL,
	/// An owner-only request (mode, passphrase, transfer, mute, lock) from a member that isn't the owner.
	NOT_OWNER,
	/// `join` with a key-check differing from the channel's — wrong (or missing) end-to-end-encryption passphrase.
	PASSPHRASE_MISMATCH,
	/// `join` refused because the owner has locked the channel to new members.
	CHANNEL_LOCKED,
	/// `join` refused because the channel is at its member cap (one stream index per member).
	CHANNEL_FULL,
	/// A request naming another member (signal, transfer, mute) whose target isn't addressable — not a member,
	/// or the owner itself as a mute target.
	UNKNOWN_TARGET,
	/// Not a real wire code: the deserialization fallback for a code minted by a NEWER server than this client
	/// (see the class doc). Treat as "an error happened, nothing special to do beyond showing the message".
	@JsonEnumDefaultValue
	UNKNOWN
}
