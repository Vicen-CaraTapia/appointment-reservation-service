package ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.kafka.verification;

import java.time.Duration;
import java.util.List;

import ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.config.CatedraIntegrationProperties;
import ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.kafka.exception.CatedraKafkaConnectivityException;
import ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.kafka.exception.CatedraKafkaConnectivityException.Reason;
import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.errors.AuthenticationException;
import org.apache.kafka.common.errors.AuthorizationException;
import org.apache.kafka.common.errors.TimeoutException;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.stereotype.Component;

@Component
public class CatedraKafkaConnectivityVerifier {

	private static final Duration METADATA_TIMEOUT = Duration.ofSeconds(5);

	private final ConsumerFactory<String, String> consumerFactory;
	private final CatedraIntegrationProperties.KafkaProperties properties;

	public CatedraKafkaConnectivityVerifier(
			ConsumerFactory<String, String> consumerFactory,
			CatedraIntegrationProperties properties) {
		this.consumerFactory = consumerFactory;
		this.properties = properties.kafka();
	}

	public void verifyConnectivity() {
		try (var consumer = consumerFactory.createConsumer()) {
			verifyTopicMetadata(consumer.partitionsFor(properties.appointmentActionsTopic(), METADATA_TIMEOUT));
			verifyTopicMetadata(consumer.partitionsFor(properties.appointmentPhoneTopic(), METADATA_TIMEOUT));
		} catch (KafkaException exception) {
			throw new CatedraKafkaConnectivityException(classify(exception));
		}
	}

	private static void verifyTopicMetadata(List<PartitionInfo> partitions) {
		if (partitions == null || partitions.isEmpty()) {
			throw new CatedraKafkaConnectivityException(Reason.INFRASTRUCTURE);
		}
	}

	private static Reason classify(KafkaException exception) {
		Throwable cause = exception;
		while (cause != null) {
			if (cause instanceof AuthenticationException || cause instanceof AuthorizationException) {
				return Reason.ACCESS;
			}
			if (cause instanceof TimeoutException) {
				return Reason.UNAVAILABLE;
			}
			cause = cause.getCause();
		}
		return Reason.INFRASTRUCTURE;
	}
}
