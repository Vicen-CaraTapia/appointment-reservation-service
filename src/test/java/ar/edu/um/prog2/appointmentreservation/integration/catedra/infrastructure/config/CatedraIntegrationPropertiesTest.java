package ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.config;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import static org.assertj.core.api.Assertions.assertThat;

class CatedraIntegrationPropertiesTest {

	private static final String TEST_JWT = "test-only-catedra-jwt-value";

	private static final List<String> VALID_PROPERTIES = List.of(
			"catedra.group-id=test-project",
			"catedra.technical-jwt=" + TEST_JWT,
			"catedra.rest.base-url=https://catedra.example.test",
			"catedra.kafka.bootstrap-servers=kafka.example.test:9092",
			"catedra.kafka.consumer-group-id=test-project-consumer",
			"catedra.kafka.appointment-actions-topic=test.appointment.actions",
			"catedra.kafka.appointment-phone-topic=test.appointment.phone");

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
			.withUserConfiguration(TestConfiguration.class);

	@Test
	void bindsAllCatedraIntegrationProperties() {
		contextRunner.withPropertyValues(VALID_PROPERTIES.toArray(String[]::new)).run(context -> {
			assertThat(context).hasNotFailed();
			var properties = context.getBean(CatedraIntegrationProperties.class);
			assertThat(properties.getGroupId()).isEqualTo("test-project");
			assertThat(properties.getTechnicalJwt()).isEqualTo(TEST_JWT);
			assertThat(properties.getRest().getBaseUrl()).isEqualTo("https://catedra.example.test");
			assertThat(properties.getKafka().getBootstrapServers()).isEqualTo("kafka.example.test:9092");
			assertThat(properties.getKafka().getConsumerGroupId()).isEqualTo("test-project-consumer");
			assertThat(properties.getKafka().getAppointmentActionsTopic()).isEqualTo("test.appointment.actions");
			assertThat(properties.getKafka().getAppointmentPhoneTopic()).isEqualTo("test.appointment.phone");
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

	@ParameterizedTest
	@MethodSource("requiredPropertyNames")
	void rejectsBlankRequiredPropertiesWithoutExposingSecrets(String propertyName) {
		contextRunner.withPropertyValues(propertiesWithBlank(propertyName)).run(context -> {
			assertThat(context).hasFailed();
			assertThat(exceptionMessages(context.getStartupFailure())).doesNotContain(TEST_JWT);
		});
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
