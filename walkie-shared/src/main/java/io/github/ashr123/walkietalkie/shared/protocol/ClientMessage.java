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
	record Join(String channel, ChannelMode mode, String displayName, String keyCheck) implements ClientMessage {
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

	/// Change this client's display name. Validated against the same charset as [Join#displayName]; on success
	/// the server applies it and broadcasts a [ServerMessage.MemberRenamed] to the channel (including the
	/// requester, as confirmation). Only the human label changes — the session id, which is the routing
	/// identity for the floor, ownership, WebRTC and audio, is unaffected.
	@JsonTypeName("rename")
	record Rename(String displayName) implements ClientMessage {
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
