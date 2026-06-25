package io.github.ashr123.walkietalkie.server.integration;

import io.github.ashr123.walkietalkie.shared.protocol.ChannelMode;
import io.github.ashr123.walkietalkie.shared.protocol.ClientMessage;
import io.github.ashr123.walkietalkie.shared.protocol.ServerMessage;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/// WebRTC signaling relay over /ws/signal: the server relays Offer/Answer/Ice verbatim to a co-channel
/// target and stamps `from` with the sender's session id (never a client-asserted identity); unknown
/// targets and pre-join signals are rejected; binary frames and malformed text are handled safely.
class SignalingRelayIntegrationTest extends WebSocketIntegrationTestSupport {

	/// Joins Alice then Bob to a fresh signaling channel; returns [aliceId,bobId].
	private String[] joinPair(String channel,
	                          WebSocketSession sa, CollectingHandler a,
	                          WebSocketSession sb, CollectingHandler b) throws Exception {
		send(sa, new ClientMessage.Join(channel, ChannelMode.MULTI_CHANNEL_PTT, "Alice", null));
		ServerMessage.Joined joinedA = awaitType(a.messages, ServerMessage.Joined.class);
		send(sb, new ClientMessage.Join(channel, ChannelMode.MULTI_CHANNEL_PTT, "Bob", null));
		ServerMessage.Joined joinedB = awaitType(b.messages, ServerMessage.Joined.class);
		awaitType(a.messages, ServerMessage.MemberJoined.class);
		return new String[]{joinedA.selfId(), joinedB.selfId()};
	}

	@Test
	void aFullOfferAnswerIceExchangeIsRelayedVerbatimWithAServerStampedFrom() throws Exception {
		CollectingHandler a = new CollectingHandler();
		CollectingHandler b = new CollectingHandler();
		WebSocketSession sa = connect(SIGNAL, a, login());
		WebSocketSession sb = connect(SIGNAL, b, login());
		try {
			String[] ids = joinPair("rtc", sa, a, sb, b);
			String alice = ids[0], bob = ids[1];

			send(sa, new ClientMessage.Offer(bob, "sdp-offer"));
			ServerMessage.SignalOffer offer = awaitType(b.messages, ServerMessage.SignalOffer.class);
			assertEquals(alice, offer.from(), "from is the server-known sender id");
			assertEquals("sdp-offer", offer.sdp());

			send(sb, new ClientMessage.Answer(alice, "sdp-answer"));
			ServerMessage.SignalAnswer answer = awaitType(a.messages, ServerMessage.SignalAnswer.class);
			assertEquals(bob, answer.from());
			assertEquals("sdp-answer", answer.sdp());

			send(sa, new ClientMessage.IceCandidate(bob, "candidate:1 udp", "audio", 0));
			ServerMessage.SignalIce ice = awaitType(b.messages, ServerMessage.SignalIce.class);
			assertEquals(alice, ice.from());
			assertEquals("candidate:1 udp", ice.candidate());
			assertEquals("audio", ice.sdpMid());
			assertEquals(0, ice.sdpMLineIndex());
		} finally {
			sa.close(CloseStatus.NORMAL);
			sb.close(CloseStatus.NORMAL);
		}
	}

	@Test
	void signalingToAnUnknownTargetReturnsUnknownTargetToTheSender() throws Exception {
		CollectingHandler a = new CollectingHandler();
		WebSocketSession sa = connect(SIGNAL, a, login());
		try {
			send(sa, new ClientMessage.Join("rtc-unknown", ChannelMode.MULTI_CHANNEL_PTT, "Alice", null));
			awaitType(a.messages, ServerMessage.Joined.class);
			send(sa, new ClientMessage.Offer("ghost", "sdp"));
			assertEquals("unknown_target", awaitType(a.messages, ServerMessage.ErrorMessage.class).code());
		} finally {
			sa.close(CloseStatus.NORMAL);
		}
	}

	@Test
	void signalingToAMemberOfADifferentChannelReturnsUnknownTarget() throws Exception {
		CollectingHandler a = new CollectingHandler();
		CollectingHandler b = new CollectingHandler();
		WebSocketSession sa = connect(SIGNAL, a, login());
		WebSocketSession sb = connect(SIGNAL, b, login());
		try {
			send(sa, new ClientMessage.Join("rtc-x", ChannelMode.MULTI_CHANNEL_PTT, "Alice", null));
			awaitType(a.messages, ServerMessage.Joined.class);
			send(sb, new ClientMessage.Join("rtc-y", ChannelMode.MULTI_CHANNEL_PTT, "Bob", null));
			String bob = awaitType(b.messages, ServerMessage.Joined.class).selfId();

			send(sa, new ClientMessage.Offer(bob, "sdp"));   // Bob is real, but not in Alice's channel
			assertEquals("unknown_target", awaitType(a.messages, ServerMessage.ErrorMessage.class).code());
		} finally {
			sa.close(CloseStatus.NORMAL);
			sb.close(CloseStatus.NORMAL);
		}
	}

	@Test
	void signalingBeforeJoiningAnyChannelReturnsNotInChannel() throws Exception {
		CollectingHandler a = new CollectingHandler();
		WebSocketSession sa = connect(SIGNAL, a, login());
		try {
			send(sa, new ClientMessage.Offer("anyone", "sdp"));
			assertEquals("not_in_channel", awaitType(a.messages, ServerMessage.ErrorMessage.class).code());
		} finally {
			sa.close(CloseStatus.NORMAL);
		}
	}

	@Test
	void signalingToYourOwnSessionIdIsRelayedBackToYou() throws Exception {
		CollectingHandler a = new CollectingHandler();
		WebSocketSession sa = connect(SIGNAL, a, login());
		try {
			send(sa, new ClientMessage.Join("rtc-self", ChannelMode.MULTI_CHANNEL_PTT, "Alice", null));
			String alice = awaitType(a.messages, ServerMessage.Joined.class).selfId();
			send(sa, new ClientMessage.Offer(alice, "sdp-self"));
			ServerMessage.SignalOffer offer = awaitType(a.messages, ServerMessage.SignalOffer.class);
			assertEquals(alice, offer.from());
			assertEquals("sdp-self", offer.sdp());
		} finally {
			sa.close(CloseStatus.NORMAL);
		}
	}

	@Test
	void aBinaryFrameOnTheSignalingTransportIsIgnoredWithoutRelayOrError() throws Exception {
		CollectingHandler a = new CollectingHandler();
		CollectingHandler b = new CollectingHandler();
		WebSocketSession sa = connect(SIGNAL, a, login());
		WebSocketSession sb = connect(SIGNAL, b, login());
		try {
			joinPair("rtc-binary", sa, a, sb, b);
			sendBinary(sa, "not-audio-here".getBytes(StandardCharsets.UTF_8));
			assertNull(b.audio.poll(1, TimeUnit.SECONDS), "signaling never relays binary frames");
			assertNotReceived(a.messages, ServerMessage.ErrorMessage.class);
		} finally {
			sa.close(CloseStatus.NORMAL);
			sb.close(CloseStatus.NORMAL);
		}
	}

	@Test
	void aMalformedControlFrameOnSignalingReturnsBadMessage() throws Exception {
		CollectingHandler a = new CollectingHandler();
		WebSocketSession sa = connect(SIGNAL, a, login());
		try {
			sendRaw(sa, "<<<garbage>>>");
			assertEquals("bad_message", awaitType(a.messages, ServerMessage.ErrorMessage.class).code());
		} finally {
			sa.close(CloseStatus.NORMAL);
		}
	}

	@Test
	void signalingToAMemberWhoHasLeftReturnsUnknownTarget() throws Exception {
		CollectingHandler a = new CollectingHandler();
		CollectingHandler b = new CollectingHandler();
		WebSocketSession sa = connect(SIGNAL, a, login());
		WebSocketSession sb = connect(SIGNAL, b, login());
		try {
			String[] ids = joinPair("rtc-left", sa, a, sb, b);
			send(sb, new ClientMessage.Leave());
			assertEquals(ids[1], awaitType(a.messages, ServerMessage.MemberLeft.class).memberId());

			send(sa, new ClientMessage.Offer(ids[1], "sdp"));   // Bob is gone now
			assertEquals("unknown_target", awaitType(a.messages, ServerMessage.ErrorMessage.class).code());
		} finally {
			sa.close(CloseStatus.NORMAL);
			sb.close(CloseStatus.NORMAL);
		}
	}
}
