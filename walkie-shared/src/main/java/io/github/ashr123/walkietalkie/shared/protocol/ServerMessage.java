package io.github.ashr123.walkietalkie.shared.protocol;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.JsonTypeName;

import java.util.List;

/**
 * Control-plane messages sent by the server to a client (as JSON text frames).
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
public sealed interface ServerMessage {

	/**
	 * Acknowledges a successful join and snapshots the current channel membership.
	 */
	@JsonTypeName("joined")
	record Joined(String selfId, String channel, ChannelMode mode, List<MemberInfo> members)
			implements ServerMessage {
	}

	/**
	 * A new participant joined the channel.
	 */
	@JsonTypeName("memberJoined")
	record MemberJoined(MemberInfo member) implements ServerMessage {
	}

	/**
	 * A participant left the channel (or disconnected).
	 */
	@JsonTypeName("memberLeft")
	record MemberLeft(String memberId) implements ServerMessage {
	}

	/**
	 * The floor was granted to you; you may transmit.
	 */
	@JsonTypeName("floorGranted")
	record FloorGranted() implements ServerMessage {
	}

	/**
	 * Your floor request was refused because someone else is holding it.
	 */
	@JsonTypeName("floorDenied")
	record FloorDenied(String currentHolderId, String reason) implements ServerMessage {
	}

	/**
	 * Another member took the floor and is now talking.
	 */
	@JsonTypeName("floorTaken")
	record FloorTaken(String holderId) implements ServerMessage {
	}

	/**
	 * The floor is now free.
	 */
	@JsonTypeName("floorIdle")
	record FloorIdle() implements ServerMessage {
	}

	/**
	 * WebRTC: an SDP offer relayed from another member.
	 */
	@JsonTypeName("signalOffer")
	record SignalOffer(String from, String sdp) implements ServerMessage {
	}

	/**
	 * WebRTC: an SDP answer relayed from another member.
	 */
	@JsonTypeName("signalAnswer")
	record SignalAnswer(String from, String sdp) implements ServerMessage {
	}

	/**
	 * WebRTC: an ICE candidate relayed from another member.
	 */
	@JsonTypeName("signalIce")
	record SignalIce(String from, String candidate, String sdpMid, Integer sdpMLineIndex)
			implements ServerMessage {
	}

	/**
	 * A problem occurred while processing a client request.
	 */
	@JsonTypeName("error")
	record ErrorMessage(String code, String message) implements ServerMessage {
	}
}
