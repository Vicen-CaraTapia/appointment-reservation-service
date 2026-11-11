package ar.edu.um.prog2.appointmentreservation.integration.catalog.infrastructure.config;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.config.CatedraIntegrationProperties;
import ar.edu.um.prog2.appointmentreservation.support.CatalogIntegrationTestSupport;
import ar.edu.um.prog2.appointmentreservation.support.CatedraIntegrationTestSupport;
import ar.edu.um.prog2.appointmentreservation.user.infrastructure.security.FinalUserJwtProperties;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(OutputCaptureExtension.class)
class CatalogIntegrationPropertiesTest {

	private static final String PRIVATE_LOCATION = "file:/not-present/test-only-private.pem";
	private static final String PRIVATE_INPUT = "test-only-sensitive-input";
	private static final List<String> VALID_PROPERTIES = List.of(
			"catalog.integration.base-url=https://catalog.example.test",
			"catalog.integration.connect-timeout=5s",
			"catalog.integration.read-timeout=30s",
			"catalog.integration.private-key-location=" + PRIVATE_LOCATION,
			"catalog.integration.issuer=appointment-reservation-service",
			"catalog.integration.audience=catalog-sync-service");

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
			.withInitializer(context -> {
				var sources = context.getEnvironment().getPropertySources();
				sources.remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
				sources.remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
			})
			.withUserConfiguration(PropertiesConfiguration.class);

	@Test
	void bindsExternalEnvironmentThroughApplicationYamlWithoutLoadingAKeyOrContactingCatalog() {
		contextRunner.withInitializer(context -> context.getEnvironment().getPropertySources().addFirst(
				new SystemEnvironmentPropertySource("syntheticEnvironment", Map.of(
						"CATALOG_BASE_URL", "https://catalog.example.test",
						"CATALOG_CONNECT_TIMEOUT", "5s",
						"CATALOG_READ_TIMEOUT", "30s",
						"INTERNAL_CATALOG_JWT_PRIVATE_KEY_LOCATION", PRIVATE_LOCATION,
						"INTERNAL_CATALOG_JWT_ISSUER", "appointment-reservation-service",
						"INTERNAL_CATALOG_JWT_AUDIENCE", "catalog-sync-service"))))
				.withInitializer(new ConfigDataApplicationContextInitializer())
				.run(context -> {
					assertThat(context).hasNotFailed();
					var properties = context.getBean(CatalogIntegrationProperties.class);
					assertThat(properties.baseUrl()).isEqualTo("https://catalog.example.test");
					assertThat(properties.connectTimeout()).isEqualTo(Duration.ofSeconds(5));
					assertThat(properties.readTimeout()).isEqualTo(Duration.ofSeconds(30));
					assertThat(properties.privateKeyLocation()).isEqualTo(PRIVATE_LOCATION);
					assertThat(properties.issuer()).isEqualTo("appointment-reservation-service");
					assertThat(properties.audience()).isEqualTo("catalog-sync-service");
					assertThat(properties.toString()).isEqualTo("CatalogIntegrationProperties[redacted]");
				});
	}

	@ParameterizedTest
	@MethodSource("requiredProperties")
	void rejectsMissingRequiredValues(String property) {
		contextRunner.withPropertyValues(without(property)).run(context -> assertThat(context).hasFailed());
	}

	@ParameterizedTest
	@MethodSource("requiredProperties")
	void rejectsBlankRequiredValues(String property) {
		assertRejected(property, " ");
	}

	@ParameterizedTest
	@ValueSource(strings = {"http://localhost:8081", "https://catalog.example.test/internal-prefix", "http://[::1]:8081"})
	void acceptsHttpBaseUrls(String url) {
		contextRunner.withPropertyValues(replacing("catalog.integration.base-url", url))
				.run(context -> assertThat(context).hasNotFailed());
	}

	@ParameterizedTest
	@ValueSource(strings = {"catalog.example.test", "/relative", "ftp://catalog.example.test", "https:///missing-host",
			"https://bad host", "https://user:test-only-sensitive-input@catalog.example.test",
			"https://catalog.example.test?token=test-only-sensitive-input", "https://catalog.example.test#fragment",
			"http://catalog.example.test:0", "http://catalog.example.test:65536"})
	void rejectsInvalidOrCredentialBearingBaseUrls(String url) {
		assertRejected("catalog.integration.base-url", url);
	}

	@ParameterizedTest
	@CsvSource({"connect-timeout,0s", "connect-timeout,-1s", "read-timeout,0s", "read-timeout,-1s"})
	void rejectsNonpositiveTimeouts(String name, String value) {
		assertRejected("catalog.integration." + name, value);
	}

	@ParameterizedTest
	@ValueSource(strings = {"500ms", "30s"})
	void acceptsPositiveBootDurationFormats(String value) {
		contextRunner.withPropertyValues(replacing("catalog.integration.connect-timeout", value))
				.run(context -> assertThat(context).hasNotFailed());
	}

	@ParameterizedTest
	@ValueSource(strings = {"relative.pem", "file:relative.pem", "classpath:private.pem", "https://example.test/private.pem",
			"file:/private.pem?token=test-only-sensitive-input", "-----BEGIN PRIVATE KEY-----test-only-sensitive-input"})
	void rejectsNonFileLocationsAndInlineKeyMaterialWithoutEchoingInput(String location) {
		assertRejected("catalog.integration.private-key-location", location);
	}

	@Test
	void keepsCatalogCatedraAndFinalUserConfigurationIndependent() {
		contextRunner.withUserConfiguration(ExistingPropertiesConfiguration.class)
				.withInitializer(CatalogIntegrationTestSupport::configure)
				.withInitializer(CatedraIntegrationTestSupport::configure)
				.withPropertyValues("appointment.security.final-user-jwt.base64-secret=" + PRIVATE_INPUT)
				.run(context -> {
					assertThat(context).hasNotFailed();
					assertThat(context.getBean(CatalogIntegrationProperties.class).audience()).isEqualTo("catalog-sync-service");
					assertThat(context.getBean(CatedraIntegrationProperties.class).technicalJwt()).isEqualTo("not-a-real-catedra-token");
					assertThat(context.getBean(FinalUserJwtProperties.class).getBase64Secret()).isEqualTo(PRIVATE_INPUT);
					assertThat(context.getBean(CatalogIntegrationProperties.class).toString())
							.doesNotContain(PRIVATE_INPUT, "not-a-real-catedra-token", "appointment-test-private.pem");
				});
	}

	@AfterEach
	void doesNotLogSensitiveInput(CapturedOutput output) {
		assertThat(output.getAll()).doesNotContain(PRIVATE_INPUT, "-----BEGIN PRIVATE KEY-----", PRIVATE_LOCATION);
	}

	private void assertRejected(String property, String value) {
		contextRunner.withPropertyValues(replacing(property, value)).run(context -> {
			assertThat(context).hasFailed();
			for (var cause = context.getStartupFailure(); cause != null; cause = cause.getCause()) {
				assertThat(cause.getMessage()).doesNotContain(PRIVATE_INPUT, "-----BEGIN PRIVATE KEY-----", PRIVATE_LOCATION);
			}
		});
	}

	static Stream<String> requiredProperties() {
		return VALID_PROPERTIES.stream().map(value -> value.substring(0, value.indexOf('=')));
	}

	private static String[] without(String property) {
		return VALID_PROPERTIES.stream().filter(value -> !value.startsWith(property + "=")).toArray(String[]::new);
	}

	private static String[] replacing(String property, String value) {
		return Stream.concat(Arrays.stream(without(property)), Stream.of(property + "=" + value)).toArray(String[]::new);
	}

	@org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods = false)
	@EnableConfigurationProperties(CatalogIntegrationProperties.class)
	static class PropertiesConfiguration {
		@Bean
		static LocalValidatorFactoryBean configurationPropertiesValidator() {
			return new LocalValidatorFactoryBean();
		}
	}

	@org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods = false)
	@EnableConfigurationProperties({CatedraIntegrationProperties.class, FinalUserJwtProperties.class})
	static class ExistingPropertiesConfiguration {
	}
}
