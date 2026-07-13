package io.github.ashr123.walkietalkie.server.transport;

import io.github.ashr123.walkietalkie.server.config.WalkieProperties;
import io.github.ashr123.walkietalkie.server.session.ClientSession;
import org.jspecify.annotations.NonNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.Map;

/// Captures the `channel` query param from the WebSocket handshake URL into the session attributes, so
/// [BaseWalkieHandler] can pin it onto the [ClientSession] ([ClientSession#handshakeChannel]). That value is the
/// routing key a channel-affinity ingress consistent-hashes on to pick the owning instance.
///
/// When [WalkieProperties#channelAffinity()] is enabled the param is REQUIRED: a handshake without it could not
/// have been routed to the correct instance, so it is refused (400) rather than risk splitting a channel across
/// instances. Single-instance (the default) the param is optional and purely informational.
@Component
public class ChannelHandshakeInterceptor implements HandshakeInterceptor {

	static final String HANDSHAKE_CHANNEL_ATTR = "walkie.handshakeChannel";

	private final WalkieProperties properties;

	public ChannelHandshakeInterceptor(WalkieProperties properties) {
		this.properties = properties;
	}

	@Override
	public boolean beforeHandshake(@NonNull ServerHttpRequest request, @NonNull ServerHttpResponse response,
	                               @NonNull WebSocketHandler wsHandler, @NonNull Map<String, Object> attributes) {
		String channel = UriComponentsBuilder.fromUri(request.getURI()).build().getQueryParams().getFirst("channel");
		if (channel == null || channel.isBlank()) {
			if (properties.channelAffinity()) {
				response.setStatusCode(HttpStatus.BAD_REQUEST);
				return false;   // no routing key while affinity is on — refuse rather than land on the wrong instance
			}
			return true;   // single instance: the param is optional
		}
		attributes.put(HANDSHAKE_CHANNEL_ATTR, channel);
		return true;
	}

	@Override
	public void afterHandshake(@NonNull ServerHttpRequest request, @NonNull ServerHttpResponse response,
	                           @NonNull WebSocketHandler wsHandler, Exception exception) {
		// nothing to do after the handshake completes
	}
}
