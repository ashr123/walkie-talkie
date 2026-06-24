package io.github.ashr123.walkietalkie.server.floor;

import io.github.ashr123.option.Some;
import io.github.ashr123.walkietalkie.server.channel.Channel;
import io.github.ashr123.walkietalkie.server.session.ClientSession;
import io.github.ashr123.walkietalkie.shared.protocol.ChannelMode;
import org.springframework.stereotype.Service;

/// Arbitrates the push-to-talk floor for half-duplex channels. Full-duplex channels have no floor,
/// so requests are trivially granted and releases are no-ops.
@Service
public class FloorControlService {

	public FloorResult requestFloor(Channel channel, ClientSession requester) {
		return channel.mode() == ChannelMode.FULL_DUPLEX || channel.tryAcquireFloor(requester.id())
				? new FloorResult.Granted()
				: channel.floorHolder() instanceof Some(String holder)
				  ? new FloorResult.Denied(holder)
				  : new FloorResult.Denied(null);
	}

	public boolean releaseFloor(Channel channel, ClientSession holder) {
		return channel.releaseFloor(holder.id());
	}
}
