package ar.edu.um.prog2.appointmentreservation.integration.catalog.infrastructure.config;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.Key;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.web.client.RestClient;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import ar.edu.um.prog2.appointmentreservation.integration.catalog.infrastructure.security.CatalogInternalTokenIssuer;
import ar.edu.um.prog2.appointmentreservation.integration.catalog.infrastructure.rest.client.CatalogProfessionalClient;
import ar.edu.um.prog2.appointmentreservation.support.CatalogIntegrationTestSupport;
import ar.edu.um.prog2.appointmentreservation.support.CatalogJwtTestSupport;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(OutputCaptureExtension.class)
class CatalogIntegrationConfigurationTest {

	@TempDir
	Path directory;

	private Path keyFile;

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
			.withInitializer(context -> {
				var sources = context.getEnvironment().getPropertySources();
				sources.remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
				sources.remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
			})
			.withUserConfiguration(PropertiesConfiguration.class, CatalogIntegrationConfiguration.class)
			.withPropertyValues(
					"catalog.integration.base-url=https://catalog.example.test",
					"catalog.integration.connect-timeout=5s",
					"catalog.integration.read-timeout=30s",
					"catalog.integration.issuer=appointment-reservation-service",
					"catalog.integration.audience=catalog-sync-service",
					"catalog.integration.token-ttl=5m");

	@BeforeEach
	void createKey() throws IOException {
		keyFile = CatalogJwtTestSupport.writePrivateKey(directory, CatalogJwtTestSupport.signingKeyPair().getPrivate());
	}

	@Test
	void eagerlyInitializesOneIssuerWithoutPublishingKeysOrEncoderBeans() {
		contextRunner.withPropertyValues("catalog.integration.private-key-location=" + keyFile.toUri())
				.run(context -> {
					assertThat(context).hasNotFailed();
					assertThat(context).hasSingleBean(CatalogInternalTokenIssuer.class);
					assertThat(context).hasSingleBean(CatalogProfessionalClient.class);
					assertThat(context.getBean(CatalogInternalTokenIssuer.class).issue()).isNotBlank();
					assertThat(context.getBeansOfType(Key.class)).isEmpty();
					assertThat(context.getBeansOfType(JwtEncoder.class)).isEmpty();
					assertThat(context.getBeansOfType(RestClient.class)).isEmpty();
				});
	}

	@Test
	void missingFileFailsDuringStartupBeforeAnyTokenIsRequested() {
		assertStartupFailure(directory.resolve("missing-private.pem").toUri().toString());
	}

	@Test
	void invalidKeyFailsDuringStartupWithoutExposingParserDiagnostics() throws IOException {
		Files.writeString(keyFile, "-----BEGIN PRIVATE KEY-----\ntest-only-sensitive-key-input\n-----END PRIVATE KEY-----");
		assertStartupFailure(keyFile.toUri().toString());
	}

	@Test
	void aMissingLocationFailsConfigurationValidation() {
		contextRunner.run(context -> assertThat(context).hasFailed());
	}

	@Test
	void ephemeralFixtureInitializesTheIssuerAndRemovesItsFileWhenTheContextCloses() {
		var location = new AtomicReference<Path>();
		contextRunner.withInitializer(CatalogIntegrationTestSupport::configure).run(context -> {
			assertThat(context).hasNotFailed();
			location.set(Path.of(URI.create(context.getBean(CatalogIntegrationProperties.class).privateKeyLocation())));
			assertThat(location.get()).exists();
			assertThat(context.getBean(CatalogInternalTokenIssuer.class).issue()).isNotBlank();
		});
		assertThat(location.get()).doesNotExist();
		assertThat(location.get().getParent()).doesNotExist();
	}

	@AfterEach
	void doesNotLogKeyMaterialOrLocation(CapturedOutput output) {
		assertThat(output.getAll()).doesNotContain(keyFile.toString(), "test-only-sensitive-key-input",
				Base64.getEncoder().encodeToString(CatalogJwtTestSupport.signingKeyPair().getPrivate().getEncoded()));
	}

	private void assertStartupFailure(String location) {
		contextRunner.withPropertyValues("catalog.integration.private-key-location=" + location).run(context -> {
			assertThat(context).hasFailed();
			for (var cause = context.getStartupFailure(); cause != null; cause = cause.getCause()) {
				assertThat(cause.getMessage()).doesNotContain(location, directory.toString(), "test-only-sensitive-key-input");
			}
			var root = context.getStartupFailure();
			while (root.getCause() != null) {
				root = root.getCause();
			}
			assertThat(root)
					.hasMessage("Catalog internal JWT signing requires a valid external RSA private key (PEM PKCS#8, at least 2048 bits)")
					.hasNoCause();
		});
	}

	@TestConfiguration(proxyBeanMethods = false)
	@EnableConfigurationProperties(CatalogIntegrationProperties.class)
	static class PropertiesConfiguration {
		@Bean
		static LocalValidatorFactoryBean configurationPropertiesValidator() {
			return new LocalValidatorFactoryBean();
		}
	}
}
