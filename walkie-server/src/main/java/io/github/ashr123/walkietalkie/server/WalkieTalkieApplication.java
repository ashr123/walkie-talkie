package io.github.ashr123.walkietalkie.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;
import org.springframework.scheduling.annotation.EnableScheduling;

/// Auth is our own stateless bearer-token scheme (`AuthService` + `TokenAuthenticationFilter`), with HTTP Basic
/// and form login disabled in `SecurityConfig` — so there is no `UserDetailsService` to back. Excluding
/// [UserDetailsServiceAutoConfiguration] stops Spring Boot auto-creating a default in-memory `user` with a random
/// password printed at startup: an unused default credential we don't want lingering in memory or in the logs.
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
@ConfigurationPropertiesScan
@EnableScheduling   // drives ConnectionService#releaseExpiredFloors, the push-to-talk max-hold reclaim sweep
public class WalkieTalkieApplication {

	static void main(String... args) {
		SpringApplication.run(WalkieTalkieApplication.class, args);
	}
}
