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

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
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
		String encoded = UriComponentsBuilder.fromUri(request.getURI()).build().getQueryParams().getFirst("channel");
		// DECODED first, and that was the bug. `UriComponentsBuilder...build()` is `build(false)`, which hands the
		// query value back still percent-encoded — measured: a Hebrew channel arrives here as
		// "%D7%94%D7%97%D7%93%D7%A8" — while a Join carries the name as text. So every channel whose name needs
		// encoding (any non-ASCII name, or one containing a space) could never equal its own Join and was bounced with
		// CHANNEL_ROUTING_MISMATCH for a name that looked identical, forever.
		//
		// URLDecoder, i.e. form decoding, is the exact inverse of what both clients send: `URLEncoder.encode` in the
		// Java client writes a space as `+`, `encodeURIComponent` in the browser writes it as `%20`, and both reduce to
		// a space here. The `+`-means-space ambiguity that makes form decoding wrong for a general URI cannot arise,
		// because a channel name is `[\p{L}\p{M}\p{N} _-]{1,64}` and a literal `+` is not in it.
		//
		// Then canonicalised with ConnectionService's OWN function rather than a smaller copy of it: the affinity
		// comparison is between this value and the canonicalised Join, so anything less than the same reduction —
		// it also collapses whitespace runs — is the same class of bug one step further along.
		String channel = encoded == null
				? null
				: ConnectionService.canonicalChannelName(URLDecoder.decode(encoded, StandardCharsets.UTF_8));
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
