package ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.kafka.producer;

import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.kafka.support.ProducerListener;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.test.util.ReflectionTestUtils;
import org.apache.kafka.clients.producer.ProducerRecord;
import ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.kafka.config.CatedraKafkaConfiguration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import tools.jackson.databind.json.JsonMapper;

import ar.edu.um.prog2.appointmentreservation.reservation.application.model.PhoneSubmissionIntent;
import ar.edu.um.prog2.appointmentreservation.reservation.application.exception.PhonePublicationException;
import ar.edu.um.prog2.appointmentreservation.reservation.application.exception.PhonePublicationException.Reason;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@ExtendWith(OutputCaptureExtension.class)
class CatedraPhoneSubmissionPublisherTest {
	@SuppressWarnings("unchecked")
	private final KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
	private final CatedraPhoneSubmissionPublisher publisher = new CatedraPhoneSubmissionPublisher(kafka, "external.phone.topic", "test-group");
	private final CompletableFuture<SendResult<String, String>> broker = new CompletableFuture<>();

	@Test
	void sendsTheOfficialEnvelopeWithStoredIdentityAndWaitsForBrokerAcknowledgment() {
		when(kafka.send(anyString(), anyString(), anyString())).thenReturn(broker);
		var result = publisher.publish(intent("test-group", PhoneSubmissionIntent.State.PENDING)).toCompletableFuture();
		assertThat(result).isNotDone();
		var payload = org.mockito.ArgumentCaptor.forClass(String.class);
		verify(kafka).send(eq("external.phone.topic"), eq("process-1"), payload.capture());
		var json = JsonMapper.builder().build().readTree(payload.getValue());
		assertThat(json.size()).isEqualTo(9);
		assertThat(json.get("eventId").asText()).isEqualTo("submission-1");
		assertThat(json.get("eventType").asText()).isEqualTo("AdditionalInformationSubmitted");
		assertThat(json.get("groupId").asText()).isEqualTo("test-group");
		assertThat(json.get("reservationProcessId").asText()).isEqualTo("process-1");
		assertThat(json.get("holdId").asText()).isEqualTo("hold-1");
		assertThat(json.get("requestEventId").asText()).isEqualTo("original-request");
		assertThat(json.get("information").size()).isEqualTo(1);
		assertThat(json.at("/information/phoneNumber").asText()).isEqualTo("00000001234");
		assertThat(json.get("occurredAt").asText()).isEqualTo("2030-01-01T12:00:00Z");
		assertThat(json.get("schemaVersion").intValue()).isEqualTo(1);
		assertThat(payload.getValue()).doesNotContain("jwt", "password", "Authorization");
		broker.complete(null);
		assertThat(result).isCompletedWithValue(null);
	}

	@Test
	void asynchronousAndSynchronousFailuresHaveNoPayloadOrPrivateCauseAndDoNotRetry() {
		when(kafka.send(anyString(), anyString(), anyString())).thenReturn(broker);
		var result = publisher.publish(intent("test-group", PhoneSubmissionIntent.State.PENDING)).toCompletableFuture();
		broker.completeExceptionally(new IllegalStateException("private phone 00000001234"));
		assertSafeFailure(result, Reason.UNKNOWN_OUTCOME);
		verify(kafka, times(1)).send(anyString(), anyString(), anyString());
		reset(kafka);
		when(kafka.send(anyString(), anyString(), anyString())).thenThrow(new IllegalStateException("private phone 00000001234"));
		assertSafeFailure(publisher.publish(intent("test-group", PhoneSubmissionIntent.State.PENDING)).toCompletableFuture(), Reason.UNKNOWN_OUTCOME);
		verify(kafka, times(1)).send(anyString(), anyString(), anyString());
	}

	@Test
	void rejectsUntrustedGroupClosedIntentAndMissingCorrelationBeforeSending() {
		assertSafeFailure(publisher.publish(intent("another-group", PhoneSubmissionIntent.State.PENDING)).toCompletableFuture(), Reason.INVALID_INTENT);
		assertSafeFailure(publisher.publish(intent("test-group", PhoneSubmissionIntent.State.CLOSED)).toCompletableFuture(), Reason.INVALID_INTENT);
		assertSafeFailure(publisher.publish(null).toCompletableFuture(), Reason.INVALID_INTENT);
		var invalid = new PhoneSubmissionIntent("id", 9, null, "process", "hold", "test-group", "1234567", Instant.now(), PhoneSubmissionIntent.State.PENDING);
		assertSafeFailure(publisher.publish(invalid).toCompletableFuture(), Reason.INVALID_INTENT);
		verifyNoInteractions(kafka);
	}

	@Test
	@SuppressWarnings("unchecked")
	void templateErrorLoggingDoesNotExposePayloadOrBrokerDiagnostics(CapturedOutput output) {
		ProducerFactory<String, String> factory = mock(ProducerFactory.class);
		var template = new CatedraKafkaConfiguration().catedraKafkaTemplate(factory);
		try {
			var listener = (ProducerListener<String, String>) ReflectionTestUtils.getField(template, "producerListener");
			listener.onError(new ProducerRecord<>("phone-topic", "process", "private-phone-00000001234"), null,
					new IllegalStateException("private-token-diagnostic"));
			assertThat(output.getAll()).contains("Catedra Kafka publication failed")
					.doesNotContain("private-phone-00000001234", "private-token-diagnostic");
		} finally { template.destroy(); }
	}

	@Test
	void resendingAnIntentRetainsExactlyTheSameKeyAndPayload() {
		when(kafka.send(anyString(), anyString(), anyString())).thenReturn(CompletableFuture.completedFuture(null));
		publisher.publish(intent("test-group", PhoneSubmissionIntent.State.PENDING)).toCompletableFuture().join();
		publisher.publish(intent("test-group", PhoneSubmissionIntent.State.PENDING)).toCompletableFuture().join();
		var payloads = org.mockito.ArgumentCaptor.forClass(String.class);
		verify(kafka, times(2)).send(eq("external.phone.topic"), eq("process-1"), payloads.capture());
		assertThat(payloads.getAllValues().get(0)).isEqualTo(payloads.getAllValues().get(1));
	}

	private static void assertSafeFailure(CompletableFuture<Void> future, Reason reason) {
		assertThatThrownBy(future::join).isInstanceOf(CompletionException.class).cause()
				.isInstanceOfSatisfying(PhonePublicationException.class, failure -> {
					assertThat(failure.reason()).isEqualTo(reason);
					assertThat(failure).hasNoCause();
					assertThat(failure.toString()).doesNotContain("00000001234", "private");
				});
	}
	static PhoneSubmissionIntent intent(String group, PhoneSubmissionIntent.State state) {
		return new PhoneSubmissionIntent("submission-1", 9, "original-request", "process-1", "hold-1", group,
				"00000001234", Instant.parse("2030-01-01T12:00:00Z"), state);
	}
}
