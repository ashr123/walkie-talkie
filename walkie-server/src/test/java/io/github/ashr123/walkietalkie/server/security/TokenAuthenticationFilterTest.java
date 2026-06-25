package io.github.ashr123.walkietalkie.server.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.mockito.Mockito.*;

/// When the request already carries an [org.springframework.security.core.Authentication], the filter must
/// be a no-op: it must not re-extract or re-verify a token (which would let a `?token=` query param override
/// an existing authentication). The stateless integration sockets always start unauthenticated, so this
/// short-circuit can only be driven directly.
class TokenAuthenticationFilterTest {

	private final AuthService authService = mock(AuthService.class);
	private final TokenAuthenticationFilter filter = new TokenAuthenticationFilter(authService);

	@AfterEach
	void clearContext() {
		SecurityContextHolder.clearContext();
	}

	@Test
	void anAlreadyAuthenticatedRequestIsNotReprocessed() throws Exception {
		SecurityContextHolder.getContext().setAuthentication(
				new UsernamePasswordAuthenticationToken("existing", null, List.of()));
		HttpServletRequest request = mock(HttpServletRequest.class);
		HttpServletResponse response = mock(HttpServletResponse.class);
		FilterChain chain = mock(FilterChain.class);

		filter.doFilterInternal(request, response, chain);

		verify(chain).doFilter(request, response);
		verifyNoInteractions(authService);   // never reached the token extraction / verification
	}
}
