package io.github.ashr123.walkietalkie.server.floor;

import io.github.ashr123.walkietalkie.server.channel.Channel;
import io.github.ashr123.walkietalkie.server.session.ClientSession;
import io.github.ashr123.walkietalkie.shared.protocol.ChannelMode;
import org.springframework.stereotype.Service;

/// Arbitrates the push-to-talk floor for half-duplex channels. Full-duplex channels have no floor,
/// so requests are trivially granted and releases are no-ops.
@Service
public class FloorControlService {

	public FloorResult requestFloor(Channel channel, ClientSession requester) {
		if (channel.mode() == ChannelMode.FULL_DUPLEX) {
			return new FloorResult.Granted();
		}
		if (channel.tryAcquireFloor(requester.id())) {
			return new FloorResult.Granted();
		}
		return new FloorResult.Denied(channel.floorHolder().orElse(null));
	}

	public boolean releaseFloor(Channel channel, ClientSession holder) {
		return channel.releaseFloor(holder.id());
	}
}
