package io.github.ashr123.walkietalkie.shared.protocol;

/// Public view of a newcomer waiting to be admitted to a LOCKED channel — one entry of the owner-only
/// [ServerMessage.JoinRequests] snapshot. `id` is the waiting session's id, which the owner echoes back in
/// [ClientMessage.ResolveJoinRequest] to admit or deny it.
///
/// Deliberately NOT a [MemberInfo]: that record carries a `streamId`, the real 0..254 routing index prefixed onto
/// a member's relayed audio, and a session waiting at the door has no index (it is not a member, holds no slot,
/// and its audio is dropped). Inventing one would alias a real member's audio lane.
public record JoinRequestInfo(String id, String displayName) {
}
