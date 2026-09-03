package io.github.ashr123.walkietalkie.server.config;

import org.springframework.boot.SpringBootVersion;
import org.springframework.boot.actuate.info.Info;
import org.springframework.boot.actuate.info.InfoContributor;
import org.springframework.stereotype.Component;

import java.util.Map;

/// Publishes the Spring Boot version on `/actuator/info`, so the browser client's footer can state what is ACTUALLY
/// running instead of a literal someone has to remember to edit.
///
/// Only Boot's own version needs code here: the JDK details come from Boot's built-in Java contributor, enabled with
/// `management.info.java.enabled` in `application.yml`. Boot ships no equivalent for its own version, and
/// [SpringBootVersion] reads it from the jar manifest of the running `spring-boot` artifact — so this is the running
/// framework's answer, not the build's opinion of it.
///
/// The page it feeds degrades gracefully: `index.html` carries no version text at all, and the footer simply keeps its
/// description if this endpoint is unreachable (actuator disabled, `info` not exposed, or an offline first paint).
///
/// `/actuator/info` is deliberately public — see [SecurityConfig], which permits it alongside `/actuator/health`. It
/// exposes a framework version and JDK build string, nothing about a channel, a member or a credential.
@Component
public class RuntimeInfoContributor implements InfoContributor {

	@Override
	public void contribute(Info.Builder builder) {
		// Nested under "springBoot" so the payload reads springBoot.version, matching the shape Boot's own Java
		// contributor uses (java.version). `@NonNull` is omitted deliberately: on a nested type it would have to be
		// written `Info.@NonNull Builder`, which is more noise than the annotation is worth here.
		builder.withDetail("springBoot", Map.of("version", SpringBootVersion.getVersion()));
	}
}
