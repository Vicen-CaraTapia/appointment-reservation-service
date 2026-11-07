package ar.edu.um.prog2.appointmentreservation.support;

import java.util.Map;

import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;

/** Dummy settings for tests that exercise local persistence rather than cátedra connectivity. */
public final class CatedraIntegrationTestSupport {

	private CatedraIntegrationTestSupport() {
	}

	public static void configure(ConfigurableApplicationContext context) {
		context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("catedraTest", Map.of(
				"catedra.group-id", "test-project",
				"catedra.technical-jwt", "not-a-real-catedra-token",
				"catedra.rest.base-url", "https://catedra.example.test",
				"catedra.kafka.bootstrap-servers", "kafka.example.test:9092",
				"catedra.kafka.consumer-group-id", "test-project-consumer",
				"catedra.kafka.appointment-actions-topic", "test.appointment.actions",
				"catedra.kafka.appointment-phone-topic", "test.appointment.phone")));
	}
}
