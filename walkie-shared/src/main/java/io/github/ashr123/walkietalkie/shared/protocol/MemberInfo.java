package io.github.ashr123.walkietalkie.shared.protocol;

/// Public view of a channel participant. `id` is the participant's session id,
/// also used as the routing address for WebRTC signaling.
public record MemberInfo(String id, String displayName) {
}
