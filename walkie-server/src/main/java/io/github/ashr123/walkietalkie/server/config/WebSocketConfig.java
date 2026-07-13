package io.github.ashr123.walkietalkie.server.config;

import io.github.ashr123.walkietalkie.server.transport.AudioRelayHandler;
import io.github.ashr123.walkietalkie.server.transport.ChannelHandshakeInterceptor;
import io.github.ashr123.walkietalkie.server.transport.SignalingHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

/// Registers the two transports:
///
///   - `/ws/audio` — the server relays raw audio frames between channel members.
///   - `/ws/signal` — the server relays WebRTC signaling only; media flows peer-to-peer.
///
/// Both handshakes are authenticated by the security filter chain before reaching these handlers.
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

	private final AudioRelayHandler audioRelayHandler;
	private final SignalingHandler signalingHandler;
	private final ChannelHandshakeInterceptor channelHandshakeInterceptor;
	private final WalkieProperties properties;

	public WebSocketConfig(AudioRelayHandler audioRelayHandler,
	                       SignalingHandler signalingHandler,
	                       ChannelHandshakeInterceptor channelHandshakeInterceptor,
	                       WalkieProperties properties) {
		this.audioRelayHandler = audioRelayHandler;
		this.signalingHandler = signalingHandler;
		this.channelHandshakeInterceptor = channelHandshakeInterceptor;
		this.properties = properties;
	}

	@Override
	public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
		// The interceptor captures the ?channel= routing key into the session attributes (see
		// ChannelHandshakeInterceptor) before either handler's afterConnectionEstablished runs.
		registry.addHandler(audioRelayHandler, "/ws/audio")
				.addInterceptors(channelHandshakeInterceptor)
				.setAllowedOriginPatterns(properties.allowedOrigins());
		registry.addHandler(signalingHandler, "/ws/signal")
				.addInterceptors(channelHandshakeInterceptor)
				.setAllowedOriginPatterns(properties.allowedOrigins());
	}

	@Bean
	public ServletServerContainerFactoryBean createWebSocketContainer() {
		ServletServerContainerFactoryBean container = new ServletServerContainerFactoryBean();
		container.setMaxBinaryMessageBufferSize(properties.maxAudioFrameBytes());
		container.setMaxTextMessageBufferSize(properties.maxTextMessageBytes());
		return container;
	}
}
