package io.github.ashr123.walkietalkie.server.config;

import io.github.ashr123.walkietalkie.server.security.AuthService;
import io.github.ashr123.walkietalkie.server.security.TokenAuthenticationFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import java.security.SecureRandom;

/// Stateless, token-based security. Static client assets, health checks and the login endpoint are
/// public; the WebSocket endpoints and everything else require a valid bearer token, applied by
/// [TokenAuthenticationFilter].
@Configuration
@EnableWebSecurity
public class SecurityConfig {

	@Bean
	public SecurityFilterChain securityFilterChain(HttpSecurity http, AuthService authService) {
		return http
				.csrf(AbstractHttpConfigurer::disable)
				.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				.authorizeHttpRequests(auth -> auth
						.requestMatchers("/", "/index.html", "/favicon.ico", "/favicon.svg", "/apple-touch-icon.png", "/assets/**").permitAll()
						// Permit the error dispatch so validation failures surface as 400, not 403.
						.requestMatchers("/error").permitAll()
						.requestMatchers("/actuator/health", "/actuator/info").permitAll()
						.requestMatchers(HttpMethod.POST, "/api/auth/login").permitAll()
						.requestMatchers("/ws/**").authenticated()
						.anyRequest().authenticated())
				.addFilterBefore(new TokenAuthenticationFilter(authService), UsernamePasswordAuthenticationFilter.class)
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
