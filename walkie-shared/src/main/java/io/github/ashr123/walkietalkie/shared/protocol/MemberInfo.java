package io.github.ashr123.walkietalkie.shared.protocol;

/// Public view of a channel participant. `id` is the participant's session id (also the routing address for
/// WebRTC signaling); `streamId` is the server-assigned per-channel stream index (0..254) prefixed onto this
/// member's relayed audio frames so multi-stream-capable receivers can demultiplex simultaneous talkers.
public record MemberInfo(String id, String displayName, int streamId) {

	/// Convenience for contexts without a stream index (defaults to 0); the server always supplies the real
	/// per-channel index for channel members.
	public MemberInfo(String id, String displayName) {
		this(id, displayName, 0);
	}
}
