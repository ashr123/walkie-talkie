package io.github.ashr123.walkietalkie.server.config;

import io.github.ashr123.walkietalkie.server.security.AuthService;
import io.github.ashr123.walkietalkie.server.security.TokenAuthenticationFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import java.security.SecureRandom;

/// Stateless, token-based security. Static client assets, health checks and the login endpoint are
/// public; the WebSocket endpoints and everything else require a valid bearer token, applied by
/// [TokenAuthenticationFilter].
@Configuration
@EnableWebSecurity
public class SecurityConfig {

	/// The answer an UNAUTHENTICATED request gets: 401 with `WWW-Authenticate: Bearer`, as RFC 6750 asks of a
	/// bearer-protected resource.
	///
	/// Without it Spring Security falls back to `Http403ForbiddenEntryPoint`, because nothing here registers an entry
	/// point — the two mechanisms that would, `httpBasic` and `formLogin`, are deliberately disabled. Measured before
	/// this: a handshake with no token, an expired one and a garbage one all answered 403, which says "your credential
	/// was accepted and does not permit this" about three cases where no credential was accepted at all. For a client
	/// author, and for anyone reading a proxy log, that is the wrong diagnosis.
	///
	/// `Bearer` and not `Basic` on purpose: a browser answers a `Basic` challenge with its own login dialog, and there
	/// is no interactive credential here to type into one.
	///
	/// No genuine 403 is masked. The only principal this app mints carries `ROLE_USER`, and every protected matcher asks
	/// merely for `authenticated()`, so an authenticated request is never refused on authority — 403 was unreachable as
	/// anything but this fallback. `setStatus` rather than `sendError`: the latter dispatches to `/error`, whose response
	/// need not carry the challenge header this exists to send.
	private static final AuthenticationEntryPoint BEARER_CHALLENGE = (_, response, _) -> {
		response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
		response.setStatus(HttpStatus.UNAUTHORIZED.value());
	};

	@Bean
	public SecurityFilterChain securityFilterChain(HttpSecurity http, AuthService authService) {
		return http
				.csrf(AbstractHttpConfigurer::disable)
				.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				.authorizeHttpRequests(auth -> auth
						.requestMatchers("/", "/index.html", "/favicon.ico", "/favicon.svg", "/apple-touch-icon.png", "/assets/**").permitAll()
						// Permit the error dispatch so validation failures surface as 400, not as the deny status.
						.requestMatchers("/error").permitAll()
						.requestMatchers("/actuator/health", "/actuator/info").permitAll()
						.requestMatchers(HttpMethod.POST, "/api/auth/login").permitAll()
						.requestMatchers("/ws/**").authenticated()
						.anyRequest().authenticated())
				.addFilterBefore(new TokenAuthenticationFilter(authService), UsernamePasswordAuthenticationFilter.class)
				.exceptionHandling(exceptions -> exceptions.authenticationEntryPoint(BEARER_CHALLENGE))
				.httpBasic(AbstractHttpConfigurer::disable)
				.formLogin(AbstractHttpConfigurer::disable)
				.build();
	}

	/// A single shared, thread-safe CSPRNG for the app's security infrastructure — token nonces in
	/// [AuthService] and the dev-TLS keystore password in [TlsConfiguration]. (Spring Boot does not
	/// auto-configure a `SecureRandom` bean, so we define one.)
	@Bean
	public SecureRandom secureRandom() {
		return new SecureRandom();
	}
}
