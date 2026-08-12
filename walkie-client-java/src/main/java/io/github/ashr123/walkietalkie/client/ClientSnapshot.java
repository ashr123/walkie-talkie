package io.github.ashr123.walkietalkie.client;

import io.github.ashr123.walkietalkie.shared.protocol.ChannelMode;
import io.github.ashr123.walkietalkie.shared.protocol.JoinRequestInfo;

import java.util.List;
import java.util.Map;
import java.util.Set;

/// A [WalkieClient]'s observable state as ONE immutable value, for a view to render from — see
/// [WalkieClient#snapshot].
///
/// The client holds this state as a couple of dozen separate `volatile` fields, which is right for the client: each
/// is written by one thread and read by another, and the cost of a read is nothing. It is wrong for a VIEW. Drawing
/// one roster row needs the member map, the mute set, the owner id and the floor to agree with each other, and
/// reading four volatiles in a row can straddle a change and blend two different moments — a member shown muted
/// against a mute set that no longer contains them, a crown on someone who just handed the channel over. Taking one
/// snapshot per repaint makes that impossible, for exactly the reason [WalkieClient.FloorSnapshot] already holds the
/// holder and the queue together rather than as two fields.
///
/// Deliberately NOT a mirror of every field the client owns. What is here is what a view renders; the client's
/// working state — the rekey handshake flags, the warn-once bits, the switch rollback, what the console last
/// narrated — stays private, because a view that could read it would be tempted to render it.
///
/// `transmitting` is whether OUR microphone is currently live. It is the one field here that is not server state —
/// it is the client's own audio engine — but a view needs it for the same reason the browser keeps
/// `state.transmitting`: the Talk control cannot say "release to stop" without knowing it is running. In a
/// push-to-talk channel it agrees with holding the floor; in full-duplex, where there is no floor, it is the only
/// answer there is.
///
/// There is no `iAmOwner()` helper on purpose. The client compares `selfId` against `ownerId` inline in several
/// places; a fourth copy of that comparison here would be a rule to keep in step for no gain, so a view compares
/// the two ids the same way the client does.
record ClientSnapshot(String selfId,
                      String channel,
                      ChannelMode mode,
                      String ownerId,
                      boolean channelLocked,
                      boolean floorQueueEnabled,
                      boolean muteNewMembers,
                      boolean transmitting,
                      Map<String, String> memberNames,
                      Set<String> mutedMembers,
                      WalkieClient.FloorSnapshot floor,
                      List<JoinRequestInfo> joinRequests
) {
	ClientSnapshot {
		// Copied, not aliased. `memberNames` is the client's live ConcurrentHashMap, so without this a view walking
		// a roster would see arrivals appear mid-walk — the very tearing the snapshot exists to prevent. The other
		// two are already republished wholesale by the listener thread, so copying them is belt-and-braces rather
		// than load-bearing, and cheap enough not to reason about per field.
		memberNames = Map.copyOf(memberNames);
		mutedMembers = Set.copyOf(mutedMembers);
		joinRequests = List.copyOf(joinRequests);
	}
}
