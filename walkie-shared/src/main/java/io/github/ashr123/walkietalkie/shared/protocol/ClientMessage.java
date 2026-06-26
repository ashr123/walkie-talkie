package io.github.ashr123.walkietalkie.shared.protocol;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.JsonTypeName;

/// Control-plane messages sent by a client to the server (as JSON text frames).
///
/// The live audio itself never travels as a `ClientMessage`: in WebSocket-relay mode
/// it is sent as raw binary frames, and in WebRTC mode it flows peer-to-peer. These messages
/// only carry coordination: joining/leaving, push-to-talk floor requests, and WebRTC signaling.
///
/// Jackson 3 needs only [JsonTypeInfo] on a sealed type; the permitted subtypes are
/// discovered automatically, and each carries a stable wire name via [JsonTypeName].
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
public sealed interface ClientMessage {

	/// Join (or create) a channel. For [ChannelMode#GLOBAL_PTT] the channel name is forced to `global`.
	///
	/// `keyCheck` is a short value derived from the end-to-end-encryption passphrase (see the clients'
	/// key derivation), or `null` when the member is unencrypted. The server compares it against the
	/// channel's established value to reject a member whose passphrase doesn't match — without ever
	/// learning the passphrase itself. It is *not* the key and cannot decrypt anything.
	@JsonTypeName("join")
	record Join(String channel, ChannelMode mode, String displayName, String keyCheck, Integer relayFraming) implements ClientMessage {
		/// `relayFraming`: `0`/absent (`null`) = legacy un-prefixed relay frames; `1` = the SID-prefixed
		/// multi-stream framing. It is a nullable `Integer` so a legacy client that omits the field entirely
		/// deserializes cleanly (the server treats `null` as `0`). This convenience constructor is the legacy
		/// (un-prefixed) form.
		public Join(String channel, ChannelMode mode, String displayName, String keyCheck) {
			this(channel, mode, displayName, keyCheck, 0);
		}
	}

	/// Leave the current channel without closing the connection.
	@JsonTypeName("leave")
	record Leave() implements ClientMessage {
	}

	/// Ask for the floor (permission to talk) in a push-to-talk channel.
	@JsonTypeName("requestFloor")
	record RequestFloor() implements ClientMessage {
	}

	/// Give up the floor in a push-to-talk channel.
	@JsonTypeName("releaseFloor")
	record ReleaseFloor() implements ClientMessage {
	}

	/// Ask the server to change the current channel's mode. Honored only for the channel owner.
	@JsonTypeName("changeMode")
	record ChangeMode(ChannelMode mode) implements ClientMessage {
	}

	/// WebRTC: an SDP offer aimed at another member, relayed by the server.
	@JsonTypeName("offer")
	record Offer(String target, String sdp) implements ClientMessage {
	}

	/// WebRTC: an SDP answer aimed at another member, relayed by the server.
	@JsonTypeName("answer")
	record Answer(String target, String sdp) implements ClientMessage {
	}

	/// WebRTC: an ICE candidate aimed at another member, relayed by the server.
	@JsonTypeName("ice")
	record IceCandidate(String target, String candidate, String sdpMid, Integer sdpMLineIndex)
			implements ClientMessage {
	}
}
