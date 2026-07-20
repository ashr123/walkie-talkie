package io.github.ashr123.walkietalkie.shared.protocol;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.JsonTypeName;

import java.util.List;

/// Control-plane messages sent by the server to a client (as JSON text frames).
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
public sealed interface ServerMessage {

	/// Acknowledges a successful join and snapshots the current channel mode, owner, membership and lock state.
	/// When joining an existing channel, `mode` is the channel's actual mode (the joiner adopts it). `locked` is
	/// whether the owner has locked the channel to new members — carried here so a re-snapshot (an in-place
	/// re-join) renders the state without waiting for a [ChannelLocked]. `floorQueueEnabled` is whether the
	/// owner-toggleable push-to-talk floor queue is on (see [FloorStatus]); carried here for the same reason.
	@JsonTypeName("joined")
	record Joined(String selfId,
	              String channel,
	              ChannelMode mode,
	              String ownerId,
	              boolean locked,
	              boolean floorQueueEnabled,
	              List<MemberInfo> members) implements ServerMessage {
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

	/// The owner muted or unmuted a member. Broadcast to the whole channel so everyone can render the state, and so
	/// the affected member itself learns it is muted and stops transmitting (its audio is dropped server-side
	/// regardless, but a well-behaved client also disables its talk control). `memberId` is the session id; `muted`
	/// is the new state.
	@JsonTypeName("memberMuted")
	record MemberMuted(String memberId, boolean muted) implements ServerMessage {
	}

	/// The owner locked or unlocked the channel to new members. Broadcast to the whole channel so everyone renders
	/// the state (existing members are unaffected — locking only blocks NEW joins). `locked` is the new state.
	@JsonTypeName("channelLocked")
	record ChannelLocked(boolean locked) implements ServerMessage {
	}

	/// The floor was granted to you; open your mic and transmit. An imperative "go live now" trigger sent only to
	/// the new holder — the accompanying [FloorStatus] (broadcast to everyone) is what renders who holds the floor.
	@JsonTypeName("floorGranted")
	record FloorGranted() implements ServerMessage {
	}

	/// The authoritative push-to-talk floor snapshot, broadcast to the whole channel on **every** floor change and
	/// carried (implicitly, via a fresh broadcast) after a join. `holderId` is the member currently live, or
	/// `null` when nobody is talking. `waiting` is the floor queue in FIFO order (empty when the queue is off or
	/// nobody is waiting).
	///
	/// Clients render ALL floor UI by deriving from this one message — there is deliberately no separate
	/// "reserved" field: the member currently offered the floor (its claim window ticking) is exactly
	/// `waiting.get(0)` whenever `holderId == null`, because the server reserves the head the instant the floor
	/// frees. So: `holderId == me` → you are live; `holderId == null && waiting.get(0) == me` → it is your turn;
	/// `me ∈ waiting` → you are in line at that index; else the floor is busy (someone else) or free.
	@JsonTypeName("floorStatus")
	record FloorStatus(String holderId, List<String> waiting) implements ServerMessage {
	}

	/// It is your turn: the floor is reserved for you for `claimSeconds`. An imperative "your turn — alert the
	/// user and start the claim countdown" trigger sent only to the newly reserved head; you must claim it (send
	/// [ClientMessage.RequestFloor]) within the window or the server drops you from the queue and offers the floor
	/// to the next member. The accompanying [FloorStatus] shows you as `waiting.get(0)` with `holderId == null`.
	@JsonTypeName("floorReserved")
	record FloorReserved(long claimSeconds) implements ServerMessage {
	}

	/// The channel owner turned the push-to-talk floor queue on or off. Broadcast to the whole channel so everyone
	/// renders the state; when turned off the server also clears any waiting queue (reflected in a following
	/// [FloorStatus]). `enabled` is the new state.
	@JsonTypeName("floorQueueChanged")
	record FloorQueueChanged(boolean enabled) implements ServerMessage {
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

	/// A problem occurred while processing a client request. `code` is the machine-readable reason a client may
	/// branch on (see [ErrorCode], incl. its unknown-code fallback); `message` is display-only.
	@JsonTypeName("error")
	record ErrorMessage(ErrorCode code, String message) implements ServerMessage {
	}
}
