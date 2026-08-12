package io.github.ashr123.walkietalkie.client;

import io.github.ashr123.walkietalkie.shared.protocol.ChannelMode;
import io.github.ashr123.walkietalkie.shared.protocol.JoinRequestInfo;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/// Pins the one guarantee [ClientSnapshot] makes beyond carrying fields: that it is a COPY.
///
/// The guarantee is the whole reason the record exists. A view walks the roster to draw it, and the client's roster
/// is a live `ConcurrentHashMap` written by the WebSocket listener thread — so a snapshot that merely aliased it
/// would let members appear and vanish mid-walk, which is exactly the tearing a snapshot is taken to prevent. That
/// failure is invisible in a screenshot and rare in a test, so it is asserted directly here rather than trusted.
class ClientSnapshotTest {

	private static final WalkieClient.FloorSnapshot FLOOR =
			new WalkieClient.FloorSnapshot("alice", List.of("bob", "carol"));

	private static ClientSnapshot snapshotOf(Map<String, String> members,
	                                         Set<String> muted,
	                                         List<JoinRequestInfo> requests) {
		return new ClientSnapshot(
				"self",
				"team",
				ChannelMode.MULTI_CHANNEL_PTT,
				"alice",
				false,
				true,
				false,
				false,
				members,
				muted,
				FLOOR,
				requests
		);
	}

	@Test
	void mutatingTheClientsCollectionsAfterwardsDoesNotChangeTheSnapshot() {
		Map<String, String> members = new HashMap<>(Map.of("a", "alice"));
		Set<String> muted = new HashSet<>(Set.of("a"));
		List<JoinRequestInfo> requests = new ArrayList<>(List.of(new JoinRequestInfo("z", "zoe")));
		ClientSnapshot view = snapshotOf(members, muted, requests);

		// Stand in for the listener thread doing what it does constantly: a member joins, a mute lands, a knock
		// arrives — all while a view is midway through rendering the snapshot it was handed.
		members.put("b", "bob");
		muted.remove("a");
		requests.clear();

		assertEquals(Map.of("a", "alice"), view.memberNames(), "the roster a view is drawing must not grow under it");
		assertEquals(Set.of("a"), view.mutedMembers());
		assertEquals(List.of(new JoinRequestInfo("z", "zoe")), view.joinRequests());
	}

	@Test
	void theSnapshotsOwnCollectionsAreUnmodifiable() {
		// The other direction: a view must not be able to "fix up" the model it was handed. `Map.copyOf` and friends
		// return unmodifiable views, so this is really a guard against someone swapping them for a defensive
		// `new HashMap<>(…)` later and quietly making the snapshot writable.
		ClientSnapshot view = snapshotOf(new HashMap<>(Map.of("a", "alice")), new HashSet<>(), new ArrayList<>());

		assertThrows(UnsupportedOperationException.class, () -> view.memberNames().put("b", "bob"));
		assertThrows(UnsupportedOperationException.class, () -> view.mutedMembers().add("b"));
		assertThrows(UnsupportedOperationException.class, () -> view.joinRequests().add(new JoinRequestInfo("b", "bob")));
	}

	@Test
	void theFloorTravelsAsTheOneValueItAlreadyIs() {
		// FloorSnapshot is already immutable and already pairs holder with queue — the record it is the precedent
		// for. Asserted so a future "flatten it into two fields for convenience" has to break a test first.
		ClientSnapshot view = snapshotOf(Map.of(), Set.of(), List.of());

		assertSame(FLOOR, view.floor());
		assertEquals("alice", view.floor().holder());
		assertEquals(List.of("bob", "carol"), view.floor().waiting());
	}
}
