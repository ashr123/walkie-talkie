package io.github.ashr123.walkietalkie.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling   // drives ConnectionService#releaseExpiredFloors, the push-to-talk max-hold reclaim sweep
public class WalkieTalkieApplication {

	static void main(String... args) {
		SpringApplication.run(WalkieTalkieApplication.class, args);
	}
}
