package ar.edu.um.prog2.appointmentreservation.support;

import java.util.Map;

import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;

/** Synthetic settings for tests that do not exercise Catalog connectivity or JWT signing. */
public final class CatalogIntegrationTestSupport {

	private CatalogIntegrationTestSupport() {
	}

	public static void configure(ConfigurableApplicationContext context) {
		context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("catalogTest", Map.of(
				"catalog.integration.base-url", "https://catalog.example.test",
				"catalog.integration.connect-timeout", "5s",
				"catalog.integration.read-timeout", "30s",
				"catalog.integration.private-key-location", "file:/not-present/appointment-test-private.pem",
				"catalog.integration.issuer", "appointment-reservation-service",
				"catalog.integration.audience", "catalog-sync-service")));
	}
}
