package ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.kafka;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.kafka.core.KafkaTemplate;

import ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.kafka.verification.CatedraKafkaConnectivityVerifier;
import ar.edu.um.prog2.appointmentreservation.user.infrastructure.persistence.repository.UserJpaRepository;

import static org.assertj.core.api.Assertions.assertThat;

class KafkaIntegrationTestApplicationTest {

	private final ApplicationContextRunner context = new ApplicationContextRunner()
			.withUserConfiguration(KafkaIntegrationTestApplication.class)
			.withPropertyValues(
					"catedra.group-id=test-project", "catedra.technical-jwt=test-only-token",
					"catedra.rest.base-url=https://catedra.example.test",
					"catedra.rest.connect-timeout=5s", "catedra.rest.read-timeout=30s",
					"catedra.kafka.bootstrap-servers=kafka.example.test:9092",
					"catedra.kafka.consumer-group-id=test-group",
					"catedra.kafka.appointment-actions-topic=test.actions",
					"catedra.kafka.appointment-phone-topic=test.phone");

	@Test
	void standaloneKafkaTestConfigurationIsInactiveInOtherTestContexts() {
		context.run(application -> assertThat(application).hasNotFailed()
				.doesNotHaveBean(KafkaIntegrationTestApplication.class)
				.doesNotHaveBean(CatedraKafkaConnectivityVerifier.class)
				.doesNotHaveBean(KafkaTemplate.class));
	}

	@Test
	void explicitKafkaTestProfileStillStartsWithoutJpaOrARealBroker() {
		context.withInitializer(application -> application.getEnvironment().setActiveProfiles("kafka-connectivity-test"))
				.run(application -> assertThat(application).hasNotFailed()
						.hasSingleBean(CatedraKafkaConnectivityVerifier.class)
						.hasSingleBean(KafkaTemplate.class)
						.doesNotHaveBean(DataSource.class)
						.doesNotHaveBean(UserJpaRepository.class));
	}
}
