package ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.config;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import ar.edu.um.prog2.appointmentreservation.support.CatedraIntegrationTestSupport;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(OutputCaptureExtension.class)
class CatedraIntegrationPropertiesTest {

	private static final String TEST_JWT = "test-only-catedra-jwt-value";

	private static final List<String> VALID_PROPERTIES = List.of(
			"catedra.group-id=test-project",
			"catedra.technical-jwt=" + TEST_JWT,
			"catedra.rest.base-url=https://catedra.example.test",
			"catedra.rest.connect-timeout=5s",
			"catedra.rest.read-timeout=30s",
			"catedra.kafka.bootstrap-servers=kafka.example.test:9092",
			"catedra.kafka.consumer-group-id=test-project-consumer",
			"catedra.kafka.appointment-actions-topic=test.appointment.actions",
			"catedra.kafka.appointment-phone-topic=test.appointment.phone");

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
			.withInitializer(context -> {
				// Missing-property cases must not fall back to the developer's environment or JVM settings.
				var sources = context.getEnvironment().getPropertySources();
				sources.remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
				sources.remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
			})
			.withUserConfiguration(TestConfiguration.class);

	@Test
	void bindsAllCatedraIntegrationProperties() {
		contextRunner.withPropertyValues(VALID_PROPERTIES.toArray(String[]::new)).run(context -> {
			assertThat(context).hasNotFailed();
			var properties = context.getBean(CatedraIntegrationProperties.class);
			assertThat(properties.groupId()).isEqualTo("test-project");
			assertThat(properties.technicalJwt()).isEqualTo(TEST_JWT);
			assertThat(properties.rest().baseUrl()).isEqualTo("https://catedra.example.test");
			assertThat(properties.rest().connectTimeout()).isEqualTo(Duration.ofSeconds(5));
			assertThat(properties.rest().readTimeout()).isEqualTo(Duration.ofSeconds(30));
			assertThat(properties.kafka().bootstrapServers()).isEqualTo("kafka.example.test:9092");
			assertThat(properties.kafka().consumerGroupId()).isEqualTo("test-project-consumer");
			assertThat(properties.kafka().appointmentActionsTopic()).isEqualTo("test.appointment.actions");
			assertThat(properties.kafka().appointmentPhoneTopic()).isEqualTo("test.appointment.phone");
			assertThat(properties.toString()).doesNotContain(TEST_JWT);
		});
	}

	@ParameterizedTest
	@MethodSource("requiredPropertyNames")
	void rejectsMissingRequiredPropertiesWithoutExposingSecrets(String propertyName) {
		contextRunner.withPropertyValues(propertiesWithout(propertyName)).run(context -> {
			assertThat(context).hasFailed();
			assertThat(exceptionMessages(context.getStartupFailure())).doesNotContain(TEST_JWT);
		});
	}

	@Test
	void acceptsDummySettingsUsedByPostgresIntegrationTests() {
		contextRunner.withInitializer(CatedraIntegrationTestSupport::configure).run(context -> {
			assertThat(context).hasNotFailed();
			assertThat(context).hasSingleBean(CatedraIntegrationProperties.class);
		});
	}

	@ParameterizedTest
	@MethodSource("requiredPropertyNames")
	void rejectsBlankRequiredPropertiesWithoutExposingSecrets(String propertyName) {
		contextRunner.withPropertyValues(propertiesWithBlank(propertyName)).run(context -> {
			assertThat(context).hasFailed();
			assertThat(exceptionMessages(context.getStartupFailure())).doesNotContain(TEST_JWT);
		});
	}

	@ParameterizedTest
	@CsvSource({
			"catedra.rest.connect-timeout, 0s",
			"catedra.rest.connect-timeout, -1s",
			"catedra.rest.connect-timeout, invalid-duration",
			"catedra.rest.connect-timeout, ' '",
			"catedra.rest.read-timeout, 0s",
			"catedra.rest.read-timeout, -1s",
			"catedra.rest.read-timeout, invalid-duration",
			"catedra.rest.read-timeout, ' '"
	})
	void rejectsInvalidTimeoutsWithoutExposingSecrets(String propertyName, String value) {
		var properties = Stream.concat(Arrays.stream(propertiesWithout(propertyName)),
				Stream.of(propertyName + "=" + value)).toArray(String[]::new);
		contextRunner.withPropertyValues(properties).run(context -> {
			assertThat(context).hasFailed();
			assertThat(exceptionMessages(context.getStartupFailure())).doesNotContain(TEST_JWT);
		});
	}

	@AfterEach
	void neverLogsTheTechnicalJwt(CapturedOutput output) {
		assertThat(output.getAll()).doesNotContain(TEST_JWT, "not-a-real-catedra-token");
	}

	static Stream<String> requiredPropertyNames() {
		return VALID_PROPERTIES.stream().map(property -> property.substring(0, property.indexOf('=')));
	}

	private static String[] propertiesWithout(String omittedProperty) {
		return VALID_PROPERTIES.stream()
				.filter(property -> !property.startsWith(omittedProperty + "="))
				.toArray(String[]::new);
	}

	private static String[] propertiesWithBlank(String blankProperty) {
		var remainingProperties = Arrays.stream(propertiesWithout(blankProperty)).toList();
		return Stream.concat(remainingProperties.stream(), Stream.of(blankProperty + "="))
				.toArray(String[]::new);
	}

	private static String exceptionMessages(Throwable failure) {
		var messages = new StringBuilder();
		for (var current = failure; current != null; current = current.getCause()) {
			messages.append(current.getMessage()).append('\n');
		}
		return messages.toString();
	}

	@Configuration(proxyBeanMethods = false)
	@EnableConfigurationProperties(CatedraIntegrationProperties.class)
	static class TestConfiguration {

		@Bean
		static LocalValidatorFactoryBean configurationPropertiesValidator() {
			return new LocalValidatorFactoryBean();
		}
	}
}
