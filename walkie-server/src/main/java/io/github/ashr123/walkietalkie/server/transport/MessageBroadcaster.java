package io.github.ashr123.walkietalkie.server.transport;

import io.github.ashr123.walkietalkie.server.channel.Channel;
import io.github.ashr123.walkietalkie.server.protocol.MessageCodec;
import io.github.ashr123.walkietalkie.server.session.ClientSession;
import io.github.ashr123.walkietalkie.shared.protocol.ServerMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.stream.Stream;

/// The single place OUTBOUND control [ServerMessage]s are serialized: to one recipient ([#toOne]), to a whole
/// channel ([#toAll]), or to everyone-but-one ([#toOthers]). Each message is encoded EXACTLY ONCE and the same
/// pre-encoded JSON is handed to every recipient (via [ClientSession#sendEncoded]) — so a broadcast to N members
/// costs one encode per message, not N. When several messages are passed to toAll/toOthers they are delivered to
/// each member in argument order (a member's outbound queue is FIFO), so e.g. a mode change can fan out
/// ModeChanged + FloorStatus in one call and every member sees them in that order.
///
/// Owning all outbound encoding here is what lets [ConnectionService] stay transport-agnostic: it hands over a
/// typed message and never touches the wire format or the [MessageCodec] — [ClientSession] carries only the raw
/// [ClientSession#sendEncoded] sink, no codec of its own. A failed send to one recipient is swallowed so it can't
/// abort a fan-out to the rest. Call sites pass the SAME `channel` they already hold, so a broadcast issued under
/// `synchronized(channel)` (the passphrase/owner/lock convergence discipline) stays under that monitor — and the
/// single encode now runs there too, briefer than the per-recipient encodes it replaces.
@Component
public class MessageBroadcaster {

	private static final Logger log = LoggerFactory.getLogger(MessageBroadcaster.class);

	private final MessageCodec codec;

	public MessageBroadcaster(MessageCodec codec) {
		this.codec = codec;
	}

	/// Delivers the already-encoded frame(s) to one recipient in order, swallowing a per-recipient failure so it
	/// can't abort a fan-out to the rest.
	private static void deliver(ClientSession member, String... encoded) {
		for (String frame : encoded) {
			try {
				member.sendEncoded(frame);
			} catch (RuntimeException e) {
				log.debug("Control fan-out to {} ({}) failed: {}", member.id(), member.displayName(), e.getMessage());
			}
		}
	}

	/// Serialize each message once, then deliver them all — in argument order — to EVERY member of `channel`.
	public void toAll(Channel channel, ServerMessage... messages) {
		String[] encoded = encodeAll(messages);
		channel.forEach(member -> deliverIfStillIn(channel, member, encoded));
	}

	/// Serialize each message once, then deliver them all — in argument order — to every member of `channel`
	/// EXCEPT `excludeSessionId` (the "tell the others" case, typically excluding the member whose own action
	/// triggered the broadcast).
	public void toOthers(Channel channel, String excludeSessionId, ServerMessage... messages) {
		String[] encoded = encodeAll(messages);
		channel.forEachOther(excludeSessionId, member -> deliverIfStillIn(channel, member, encoded));
	}

	/// Delivers a channel fan-out only to a member whose CURRENT channel really is this one.
	///
	/// A member can sit in a channel's roster while already belonging to another, for a few microseconds: an
	/// in-place switch adds the session to its TARGET and departs the old channel only afterwards, deliberately, so
	/// that a refused switch cannot drop it from both. No lock on the old channel is held across that gap, so a
	/// concurrent handler there can fan out and reach a session that has already moved on.
	///
	/// The session's own channel pointer is what settles it: the join hook sets it to the target BEFORE announcing
	/// anything (`session.joinedChannel(...)` in ConnectionService's initial-state hook), so during the gap it names
	/// the target while the old channel still lists the member.
	///
	/// Filtering here rather than on the wire is what makes it cheap: no channel-scoped message carries a channel
	/// name, so a client cannot tell a stray apart from a real one — and it would not be a passing glitch if it
	/// could not, because most of these are CHANGE events with no periodic re-sync. A stray `MemberLeft` drops a real
	/// member from a roster with nothing to restore it; a stray `ModeChanged` flips a client's mode; a stray
	/// `MuteStatus` can convince a member muted in its new channel that it is not. Only `FloorStatus` self-heals.
	/// Doing it in one place covers every such message at once, and costs a string compare per recipient on the
	/// control plane only — [ConnectionService]'s audio fan-out is untouched, since a stray audio frame is
	/// self-healing noise and that loop is deliberately allocation-free.
	private static void deliverIfStillIn(Channel channel, ClientSession member, String... encoded) {
		if (channel.name().equals(member.channelName())) {
			deliver(member, encoded);
		}
	}

	/// Serialize `message` and send it to a SINGLE recipient — the non-fan-out control sends (a Joined snapshot, a
	/// floor grant/denial, an error reply, a relayed WebRTC signal). Swallows a send failure like the fan-outs.
	public void toOne(ClientSession recipient, ServerMessage message) {
		deliver(recipient, codec.encode(message));
	}

	private String[] encodeAll(ServerMessage... messages) {
		return Stream.of(messages)
				.map(codec::encode)
				.toArray(String[]::new);
	}
}
