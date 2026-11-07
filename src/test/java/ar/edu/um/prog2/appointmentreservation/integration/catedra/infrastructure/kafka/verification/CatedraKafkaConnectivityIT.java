package ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.kafka.verification;

import static org.assertj.core.api.Assertions.assertThatCode;

import ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.kafka.KafkaIntegrationTestApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(
		classes = KafkaIntegrationTestApplication.class,
		webEnvironment = SpringBootTest.WebEnvironment.NONE,
		properties = {
				"catedra.group-id=kafka-integration-test",
				"catedra.technical-jwt=not-a-real-catedra-token",
				"catedra.rest.base-url=https://catedra.example.test",
				"catedra.rest.connect-timeout=5s",
				"catedra.rest.read-timeout=30s",
				"catedra.kafka.bootstrap-servers=${CATEDRA_KAFKA_BOOTSTRAP_SERVERS}",
				"catedra.kafka.consumer-group-id=${CATEDRA_KAFKA_CONSUMER_GROUP_ID}",
				"catedra.kafka.appointment-actions-topic=${CATEDRA_KAFKA_APPOINTMENT_ACTIONS_TOPIC}",
				"catedra.kafka.appointment-phone-topic=${CATEDRA_KAFKA_APPOINTMENT_PHONE_TOPIC}"
		}
)
class CatedraKafkaConnectivityIT {

	@Test
	void catedraKafkaAppointmentTopicMetadataIsAvailable(
			@Autowired CatedraKafkaConnectivityVerifier verifier) {
		assertThatCode(verifier::verifyConnectivity).doesNotThrowAnyException();
	}
}
