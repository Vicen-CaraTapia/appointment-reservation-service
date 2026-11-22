package ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.kafka.verification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;

import ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.config.CatedraIntegrationProperties;
import ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.kafka.exception.CatedraKafkaConnectivityException;
import ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.kafka.exception.CatedraKafkaConnectivityException.Reason;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.errors.AuthenticationException;
import org.apache.kafka.common.errors.AuthorizationException;
import org.apache.kafka.common.errors.TimeoutException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.ConsumerFactory;

@ExtendWith(MockitoExtension.class)
class CatedraKafkaConnectivityVerifierTest {

	private static final String ACTIONS_TOPIC = "assigned.actions.topic";
	private static final String PHONE_TOPIC = "assigned.phone.topic";
	private static final Duration TIMEOUT = Duration.ofSeconds(5);

	@Mock
	private ConsumerFactory<String, String> consumerFactory;

	@Mock
	private Consumer<String, String> consumer;

	private CatedraKafkaConnectivityVerifier verifier;

	@BeforeEach
	void setUp() {
		when(consumerFactory.createConsumer()).thenReturn(consumer);
		var kafka = new CatedraIntegrationProperties.KafkaProperties(
				"localhost:19092", "test-consumer-group", ACTIONS_TOPIC, PHONE_TOPIC);
		var properties = new CatedraIntegrationProperties(
				"test-project", "not-a-real-token",
				new CatedraIntegrationProperties.RestProperties("https://example.test", Duration.ofSeconds(5), Duration.ofSeconds(30)),
				kafka);
		verifier = new CatedraKafkaConnectivityVerifier(consumerFactory, properties);
	}

	@Test
	void checksMetadataForBothConfiguredTopicsAndClosesConsumerWithoutReceivingMessages() {
		var partitions = List.of(mock(PartitionInfo.class));
		when(consumer.partitionsFor(ACTIONS_TOPIC, TIMEOUT)).thenReturn(partitions);
		when(consumer.partitionsFor(PHONE_TOPIC, TIMEOUT)).thenReturn(partitions);

		verifier.verifyConnectivity();

		verify(consumer).partitionsFor(ACTIONS_TOPIC, TIMEOUT);
		verify(consumer).partitionsFor(PHONE_TOPIC, TIMEOUT);
		verify(consumer).close();
		verifyNoMoreInteractions(consumer);
	}

	@Test
	void treatsMissingTopicMetadataAsInfrastructureFailure() {
		when(consumer.partitionsFor(ACTIONS_TOPIC, TIMEOUT)).thenReturn(List.of());

		var exception = catchThrowableOfType(verifier::verifyConnectivity, CatedraKafkaConnectivityException.class);

		assertThat(exception.reason()).isEqualTo(Reason.INFRASTRUCTURE);
		verify(consumer).close();
	}

	@Test
	void classifiesNestedAuthenticationFailureWithoutExposingDiagnostics() {
		assertFailure(new KafkaException("private-diagnostic", new AuthenticationException("private-diagnostic")),
				Reason.ACCESS);
	}

	@Test
	void classifiesAuthorizationFailureAsAccessFailure() {
		assertFailure(new AuthorizationException("private-diagnostic"), Reason.ACCESS);
	}

	@Test
	void classifiesNestedTimeoutAsUnavailable() {
		assertFailure(new KafkaException("private-diagnostic", new TimeoutException("private-diagnostic")),
				Reason.UNAVAILABLE);
	}

	@Test
	void classifiesOtherKafkaFailuresAsInfrastructureFailures() {
		assertFailure(new KafkaException("private-diagnostic"), Reason.INFRASTRUCTURE);
	}

	private void assertFailure(KafkaException failure, Reason expectedReason) {
		when(consumer.partitionsFor(ACTIONS_TOPIC, TIMEOUT)).thenThrow(failure);

		var exception = catchThrowableOfType(verifier::verifyConnectivity, CatedraKafkaConnectivityException.class);

		assertThat(exception.reason()).isEqualTo(expectedReason);
		assertThat(exception.getMessage()).doesNotContain("private-diagnostic");
		assertThat(exception.getCause()).isNull();
		verify(consumer).close();
	}
}
