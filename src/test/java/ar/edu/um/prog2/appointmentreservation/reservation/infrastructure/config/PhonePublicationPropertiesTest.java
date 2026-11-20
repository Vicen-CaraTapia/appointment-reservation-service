package ar.edu.um.prog2.appointmentreservation.reservation.infrastructure.config;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import static org.assertj.core.api.Assertions.assertThat;

class PhonePublicationPropertiesTest {
	private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(Settings.class);
	@Test
	void defaultsAreBoundedAndExternalDurationFormatsAreSupported() {
		runner.run(context -> {
			assertThat(context).hasNotFailed();
			assertThat(context.getBean(PhonePublicationProperties.class).maxAttempts()).isEqualTo(3);
		});
		runner.withPropertyValues("appointment.phone-publication.max-attempts=5", "appointment.phone-publication.lease=2m",
				"appointment.phone-publication.acknowledgment-timeout=5s", "appointment.phone-publication.poll-interval=20s")
				.run(context -> {
					assertThat(context).hasNotFailed();
					assertThat(context.getBean(PhonePublicationProperties.class).acknowledgmentTimeout()).isEqualTo(Duration.ofSeconds(5));
					assertThat(context.getBean(PhonePublicationProperties.class).maxAttempts()).isEqualTo(5);
				});
	}
	@ParameterizedTest
	@ValueSource(strings = {"max-attempts=0", "max-attempts=101", "batch-size=0", "batch-size=101", "lease=1s",
		"acknowledgment-timeout=0s", "retry-delay=-1s", "poll-interval=0s", "lease=bogus"})
	void rejectsInvalidRecoverySettings(String property) {
		runner.withPropertyValues("appointment.phone-publication." + property).run(context -> assertThat(context).hasFailed());
	}
	@Configuration(proxyBeanMethods = false)
	@EnableConfigurationProperties(PhonePublicationProperties.class)
	static class Settings { }
}
