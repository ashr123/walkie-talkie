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

	/// Ask the server to change (rotate, set, or clear) the current channel's end-to-end-encryption passphrase.
	/// Honored only for the channel owner (a non-owner gets `not_owner`; sending it before joining gets
	/// `not_in_channel`). `keyCheck` is the key-check value derived from the **new** passphrase (see the clients'
	/// key derivation), or `null` to make the channel unencrypted. As with [Join#keyCheck] the server never sees
	/// the passphrase itself — it only records the new key-check and broadcasts a [ServerMessage.PassphraseChanged].
	///
	/// `wrappedKey` is an OPTIONAL convenience: the new passphrase encrypted under the channel's **OLD** key
	/// (base64 of an AES-256-GCM blob the owner computes with the key it currently holds), or `null`. When
	/// present, connected members decrypt it with the old key they already hold and adopt the new passphrase
	/// automatically — so a rotation needs no out-of-band step. The server still never learns the passphrase (it
	/// relays the blob opaquely, exactly like the audio it forwards). It is `null` when the owner opts out (a
	/// revocation-style rotation that forces out-of-band re-entry, since anyone holding the old key could unwrap
	/// it), when there is no old key (a plaintext→encrypted *enable*), or when disabling encryption.
	@JsonTypeName("changePassphrase")
	record ChangePassphrase(String keyCheck, String wrappedKey) implements ClientMessage {
	}

	/// Ask the server to hand channel ownership to another member. Honored only for the current owner (a
	/// non-owner gets `not_owner`; sending it before joining gets `not_in_channel`); `newOwnerId` must be the
	/// session id of a **current member** of the channel (else `unknown_target`). On success the server reassigns
	/// the owner and broadcasts a [ServerMessage.OwnerChanged] to the whole channel — the same message a
	/// departure-triggered auto-election sends — so the new owner gains the owner-only controls and the old owner
	/// loses them. The server-managed `global` room has a sentinel owner, so a transfer there is refused.
	@JsonTypeName("transferOwnership")
	record TransferOwnership(String newOwnerId) implements ClientMessage {
	}

	/// Change this client's display name. Validated against the same charset as [Join#displayName]; on success
	/// the server applies it and broadcasts a [ServerMessage.MemberRenamed] to the channel (including the
	/// requester, as confirmation). Only the human label changes — the session id, which is the routing
	/// identity for the floor, ownership, WebRTC and audio, is unaffected.
	@JsonTypeName("rename")
	record Rename(String displayName) implements ClientMessage {
	}

	/// Owner-only moderation: mute or unmute one member's relay audio. `memberId` is a current member's session id;
	/// `muted` is true to mute, false to unmute. A non-owner gets `not_owner`; an unknown target — or the owner
	/// itself, which can't be muted — gets `unknown_target`. On success the server records the state, DROPS that
	/// member's relayed audio while muted (enforced server-side, so a client can't talk its way around it), frees
	/// the floor if the muted member was holding it, and broadcasts a [ServerMessage.MemberMuted]. Enforcement is
	/// relay-only — WebRTC media is peer-to-peer, so mute there is best-effort at the muted client.
	@JsonTypeName("muteMember")
	record MuteMember(String memberId, boolean muted) implements ClientMessage {
	}

	/// Owner-only moderation: mute or unmute EVERY other member of the channel at once (the owner is never muted).
	/// Same server enforcement and per-member [ServerMessage.MemberMuted] broadcast (one for each member whose
	/// state actually changed) as [MuteMember]. A non-owner gets `not_owner`.
	@JsonTypeName("muteAll")
	record MuteAll(boolean muted) implements ClientMessage {
	}

	/// Owner-only: lock or unlock the channel to NEW members. `locked` is true to lock, false to unlock. A non-owner
	/// gets `not_owner`; sending it before joining gets `not_in_channel`. While locked, the server refuses any join
	/// from a member not already in the channel with `channel_locked` — enforced in the atomic join under the same
	/// bin lock as the passphrase-mismatch check, so it can't race a concurrent join. Existing members are
	/// unaffected (locking blocks only new joins); on success the server broadcasts a [ServerMessage.ChannelLocked].
	/// The server-managed `global` room has a sentinel owner, so a lock there is refused.
	@JsonTypeName("setLocked")
	record SetLocked(boolean locked) implements ClientMessage {
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
