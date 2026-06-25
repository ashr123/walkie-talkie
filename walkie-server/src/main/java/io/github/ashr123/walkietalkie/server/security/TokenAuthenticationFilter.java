package io.github.ashr123.walkietalkie.server.security;

import io.github.ashr123.option.Some;
import io.github.ashr123.walkietalkie.server.config.SecurityConfig;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jspecify.annotations.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/// Authentication middleware. Reads a bearer token from the `Authorization` header or, for browser
/// WebSocket handshakes that cannot set custom headers, from a `token` query parameter, and verifies its
/// signature and expiry via [AuthService] (no server-side lookup). A valid token authenticates the request
/// with a fixed principal — the participant's real identity is the per-connection WebSocket session id,
/// not anything carried here — so the security filter chain admits the protected endpoints.
///
/// Intentionally not a Spring `@`[Component]: it is constructed directly in [SecurityConfig] so it is
/// registered only within the security filter chain, avoiding the duplicate servlet-level
/// registration Spring Boot performs for `OncePerRequestFilter` beans.
public class TokenAuthenticationFilter extends OncePerRequestFilter {

	/// Constant principal name. Identity in a channel is the WebSocket session id, so the principal needs
	/// no per-user value — and the token must never be it (a credential would then leak into logs).
	private static final String PRINCIPAL = "ws-client";

	private final AuthService authService;

	public TokenAuthenticationFilter(AuthService authService) {
		this.authService = authService;
	}

	@Override
	protected void doFilterInternal(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response, @NonNull FilterChain chain)
			throws ServletException, IOException {
		if (SecurityContextHolder.getContext().getAuthentication() == null
				&& BearerTokens.extract(request.getHeader("Authorization"), request.getParameter("token")) instanceof Some(String token)
				&& authService.verify(token)) {
			SecurityContextHolder.getContext()
					.setAuthentication(new UsernamePasswordAuthenticationToken(
							PRINCIPAL,
							null,
							AuthorityUtils.createAuthorityList("ROLE_USER")
					));
		}
		chain.doFilter(request, response);
	}
}
