package ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.kafka.producer;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import org.springframework.kafka.core.KafkaTemplate;

import tools.jackson.databind.json.JsonMapper;

import ar.edu.um.prog2.appointmentreservation.reservation.application.exception.PhonePublicationException;
import ar.edu.um.prog2.appointmentreservation.reservation.application.exception.PhonePublicationException.Reason;
import ar.edu.um.prog2.appointmentreservation.reservation.application.model.PhoneSubmissionIntent;
import ar.edu.um.prog2.appointmentreservation.reservation.application.port.out.PhoneSubmissionPublisher;

/** Official v1 transport only. Does not generate identity, retry, or change local reservation state. */
public final class CatedraPhoneSubmissionPublisher implements PhoneSubmissionPublisher {
	private final KafkaTemplate<String, String> kafka;
	private final String topic;
	private final String groupId;
	private final JsonMapper json = JsonMapper.builder().build();

	public CatedraPhoneSubmissionPublisher(KafkaTemplate<String, String> kafka, String topic, String groupId) {
		this.kafka = Objects.requireNonNull(kafka);
		if (blank(topic) || blank(groupId)) { throw new IllegalArgumentException("Phone topic and technical group are required"); }
		this.topic = topic;
		this.groupId = groupId;
	}

	@Override
	public CompletionStage<Void> publish(PhoneSubmissionIntent intent) {
		if (!valid(intent)) { return CompletableFuture.failedFuture(new PhonePublicationException(Reason.INVALID_INTENT)); }
		var result = new CompletableFuture<Void>();
		try {
			var payload = json.createObjectNode();
			payload.put("eventId", intent.eventId());
			payload.put("eventType", "AdditionalInformationSubmitted");
			payload.put("groupId", groupId);
			payload.put("reservationProcessId", intent.externalProcessId());
			payload.put("holdId", intent.holdId());
			payload.put("requestEventId", intent.requestEventId());
			payload.putObject("information").put("phoneNumber", intent.normalizedPhone());
			payload.put("occurredAt", intent.occurredAt().toString());
			payload.put("schemaVersion", 1);
			kafka.send(topic, intent.externalProcessId(), json.writeValueAsString(payload)).whenComplete((sent, failure) -> {
				if (failure == null) { result.complete(null); }
				else { result.completeExceptionally(new PhonePublicationException(Reason.UNKNOWN_OUTCOME)); }
			});
		} catch (RuntimeException failure) {
			result.completeExceptionally(new PhonePublicationException(Reason.UNKNOWN_OUTCOME));
		}
		return result;
	}

	private boolean valid(PhoneSubmissionIntent intent) {
		return intent != null && intent.localProcessId() > 0 && intent.state() == PhoneSubmissionIntent.State.PENDING
				&& !blank(intent.eventId()) && !blank(intent.requestEventId()) && !blank(intent.externalProcessId())
				&& !blank(intent.holdId()) && groupId.equals(intent.technicalGroupId()) && intent.occurredAt() != null
				&& intent.normalizedPhone() != null && intent.normalizedPhone().matches("\\+?[0-9]{7,15}");
	}
	private static boolean blank(String value) { return value == null || value.isBlank(); }
}
