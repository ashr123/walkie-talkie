package io.github.ashr123.walkietalkie.server.security;

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
import java.util.Optional;

/// Authentication middleware. Reads a bearer token from the `Authorization` header or, for
/// browser WebSocket handshakes that cannot set custom headers, from a `token` query parameter.
/// A valid token populates the [SecurityContextHolder]; the security filter chain then enforces
/// authentication on the protected endpoints.
@Component
public class TokenAuthenticationFilter extends OncePerRequestFilter {

	private static final String BEARER_PREFIX = "Bearer ";

	private final AuthService authService;

	public TokenAuthenticationFilter(AuthService authService) {
		this.authService = authService;
	}

	@Override
	protected void doFilterInternal(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response, @NonNull FilterChain chain)
			throws ServletException, IOException {
		if (SecurityContextHolder.getContext().getAuthentication() == null) {
			extractToken(request)
					.flatMap(authService::resolve)
					.ifPresent(userId -> {
						UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
								userId, null, AuthorityUtils.createAuthorityList("ROLE_USER"));
						SecurityContextHolder.getContext().setAuthentication(authentication);
					});
		}
		chain.doFilter(request, response);
	}

	private Optional<String> extractToken(HttpServletRequest request) {
		String header = request.getHeader("Authorization");
		if (header != null && header.startsWith(BEARER_PREFIX)) {
			return Optional.of(header.substring(BEARER_PREFIX.length()).trim());
		}
		String query = request.getParameter("token");
		if (query != null && !query.isBlank()) {
			return Optional.of(query.trim());
		}
		return Optional.empty();
	}
}
