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
/// ModeChanged + FloorIdle in one call and every member sees them in that order.
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

	/// Serialize each message once, then deliver them all — in argument order — to EVERY member of `channel`.
	public void toAll(Channel channel, ServerMessage... messages) {
		String[] encoded = encodeAll(messages);
		channel.forEach(member -> deliver(member, encoded));
	}

	/// Serialize each message once, then deliver them all — in argument order — to every member of `channel`
	/// EXCEPT `excludeSessionId` (the "tell the others" case, typically excluding the member whose own action
	/// triggered the broadcast).
	public void toOthers(Channel channel, String excludeSessionId, ServerMessage... messages) {
		String[] encoded = encodeAll(messages);
		channel.forEachOther(excludeSessionId, member -> deliver(member, encoded));
	}

	/// Serialize `message` and send it to a SINGLE recipient — the non-fan-out control sends (a Joined snapshot, a
	/// floor grant/denial, an error reply, a relayed WebRTC signal). Swallows a send failure like the fan-outs.
	public void toOne(ClientSession recipient, ServerMessage message) {
		deliver(recipient, codec.encode(message));
	}

	private String[] encodeAll(ServerMessage... messages) {
		return Stream.of(messages)
				.map(codec::encode).
				toArray(String[]::new);
	}

	/// Delivers the already-encoded frame(s) to one recipient in order, swallowing a per-recipient failure so it
	/// can't abort a fan-out to the rest.
	private static void deliver(ClientSession member, String... encoded) {
		for (String frame : encoded) {
			try {
				member.sendEncoded(frame);
			} catch (RuntimeException e) {
				log.debug("Control fan-out to {} failed: {}", member.id(), e.getMessage());
			}
		}
	}
}
