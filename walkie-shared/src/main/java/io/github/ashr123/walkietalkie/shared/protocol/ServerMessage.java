package io.github.ashr123.walkietalkie.shared.protocol;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.JsonTypeName;

import java.util.List;

/// Control-plane messages sent by the server to a client (as JSON text frames).
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
public sealed interface ServerMessage {

	/// Acknowledges a successful join and snapshots the current channel mode, owner and membership.
	/// When joining an existing channel, `mode` is the channel's actual mode (the joiner adopts it).
	@JsonTypeName("joined")
	record Joined(String selfId, String channel, ChannelMode mode, String ownerId, List<MemberInfo> members)
			implements ServerMessage {
	}

	/// A new participant joined the channel.
	@JsonTypeName("memberJoined")
	record MemberJoined(MemberInfo member) implements ServerMessage {
	}

	/// A participant left the channel (or disconnected).
	@JsonTypeName("memberLeft")
	record MemberLeft(String memberId) implements ServerMessage {
	}

	/// A participant changed its display name. The `memberId` (session id) is unchanged — only the human label
	/// moves — so clients just update that member's name (and, for their own id, their self label).
	@JsonTypeName("memberRenamed")
	record MemberRenamed(String memberId, String displayName) implements ServerMessage {
	}

	/// The floor was granted to you; you may transmit.
	@JsonTypeName("floorGranted")
	record FloorGranted() implements ServerMessage {
	}

	/// Your floor request was refused because someone else is holding it.
	@JsonTypeName("floorDenied")
	record FloorDenied(String currentHolderId) implements ServerMessage {
	}

	/// Another member took the floor and is now talking.
	@JsonTypeName("floorTaken")
	record FloorTaken(String holderId) implements ServerMessage {
	}

	/// The floor is now free.
	@JsonTypeName("floorIdle")
	record FloorIdle() implements ServerMessage {
	}

	/// The channel's owner changed its mode; clients adopt it, reset their talk state and re-render
	/// their controls.
	@JsonTypeName("modeChanged")
	record ModeChanged(ChannelMode mode) implements ServerMessage {
	}

	/// The channel's owner changed (e.g. the previous owner disconnected); the named member may now
	/// change the mode.
	@JsonTypeName("ownerChanged")
	record OwnerChanged(String ownerId) implements ServerMessage {
	}

	/// The channel owner changed the end-to-end-encryption passphrase. `keyCheck` is the new key-check value, or
	/// `null` if the channel is now unencrypted. Every member — including the owner who initiated it — adopts the
	/// new key and verifies its derived key-check against this one. The passphrase itself is never sent in clear;
	/// the server never sees it.
	///
	/// `wrappedKey` (relayed verbatim from [ClientMessage.ChangePassphrase#wrappedKey()], or `null`) is the new
	/// passphrase encrypted under the channel's OLD key: a member that still holds the old key decrypts it and
	/// adopts the new passphrase **automatically**, verifying the result against `keyCheck`. When it is absent (the
	/// owner opted out, an enable transition, or a tampered/undecryptable blob) the member must obtain the new
	/// passphrase out-of-band and apply it. Until a member holds a key matching `keyCheck` it is muted — it neither
	/// transmits (no plaintext, no stale-key audio) nor can decode others.
	@JsonTypeName("passphraseChanged")
	record PassphraseChanged(String keyCheck, String wrappedKey) implements ServerMessage {
	}

	/// WebRTC: an SDP offer relayed from another member.
	@JsonTypeName("signalOffer")
	record SignalOffer(String from, String sdp) implements ServerMessage {
	}

	/// WebRTC: an SDP answer relayed from another member.
	@JsonTypeName("signalAnswer")
	record SignalAnswer(String from, String sdp) implements ServerMessage {
	}

	/// WebRTC: an ICE candidate relayed from another member.
	@JsonTypeName("signalIce")
	record SignalIce(String from, String candidate, String sdpMid, Integer sdpMLineIndex)
			implements ServerMessage {
	}

	/// A problem occurred while processing a client request.
	@JsonTypeName("error")
	record ErrorMessage(String code, String message) implements ServerMessage {
	}
}
