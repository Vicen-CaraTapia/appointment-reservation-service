package ar.edu.um.prog2.appointmentreservation.support;

import java.io.IOException;
import java.nio.file.Files;
import java.util.Map;

import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.core.env.MapPropertySource;

/** Isolated Catalog settings and an ephemeral signing key; no Catalog server is required. */
public final class CatalogIntegrationTestSupport implements ApplicationContextInitializer<ConfigurableApplicationContext> {

	@Override
	public void initialize(ConfigurableApplicationContext context) {
		configure(context);
	}

	public static void configure(ConfigurableApplicationContext context) {
		try {
			var directory = Files.createTempDirectory("appointment-catalog-test-");
			var key = CatalogJwtTestSupport.writePrivateKey(directory, CatalogJwtTestSupport.signingKeyPair().getPrivate());
			directory.toFile().deleteOnExit();
			key.toFile().deleteOnExit();
			context.addApplicationListener(event -> {
				if (event instanceof ContextClosedEvent) {
					try {
						Files.deleteIfExists(key);
						Files.deleteIfExists(directory);
					} catch (IOException exception) {
						throw new IllegalStateException("Cannot clean up temporary Catalog test key");
					}
				}
			});
			context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("catalogTest", Map.of(
					"catalog.integration.base-url", "https://catalog.example.test",
					"catalog.integration.connect-timeout", "5s",
					"catalog.integration.read-timeout", "30s",
					"catalog.integration.private-key-location", key.toUri().toString(),
					"catalog.integration.issuer", "appointment-reservation-service",
					"catalog.integration.audience", "catalog-sync-service",
					"catalog.integration.token-ttl", "5m")));
		} catch (IOException exception) {
			throw new IllegalStateException("Cannot create temporary Catalog test key");
		}
	}
}
