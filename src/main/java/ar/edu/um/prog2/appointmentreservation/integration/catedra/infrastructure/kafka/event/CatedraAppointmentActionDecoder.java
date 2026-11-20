package ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.kafka.event;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;

import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.kafka.exception.InvalidCatedraActionException;
import ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.kafka.exception.InvalidCatedraActionException.Reason;
import ar.edu.um.prog2.appointmentreservation.reservation.application.model.ReservationActionEvent;
import ar.edu.um.prog2.appointmentreservation.reservation.application.model.StartReservationCommand;
import ar.edu.um.prog2.appointmentreservation.reservation.domain.model.AsynchronousReservationTransitions.Signal;

/** Official v1 wire validation only. No database, state transitions or offset acknowledgment. */
public final class CatedraAppointmentActionDecoder {
	private final String expectedGroupId;
	private final JsonMapper json = JsonMapper.builder()
			.enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
			.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

	public CatedraAppointmentActionDecoder(String expectedGroupId) {
		if (expectedGroupId == null || expectedGroupId.isBlank()) {
			throw new IllegalArgumentException("Expected technical group is required");
		}
		this.expectedGroupId = expectedGroupId;
	}

	public ReservationActionEvent decode(String messageKey, String payload) {
		try {
			if (payload == null) { throw invalid(Reason.INVALID_PAYLOAD); }
			var envelope = json.readTree(payload);
			if (envelope == null || !envelope.isObject()) { throw invalid(Reason.INVALID_PAYLOAD); }
			if (integer(envelope, "schemaVersion") != 1) { throw invalid(Reason.UNSUPPORTED_VERSION); }
			Signal signal = signal(text(envelope, "eventType"));
			String processId = text(envelope, "reservationProcessId");
			if (!processId.equals(messageKey)) { throw invalid(Reason.KEY_MISMATCH); }
			if (signal != Signal.PHONE_REQUESTED && !expectedGroupId.equals(text(envelope, "groupId"))) {
				throw invalid(Reason.GROUP_MISMATCH);
			}
			validateSpecificFields(envelope, signal);
			return evidence(envelope, signal, processId);
		} catch (JacksonException | IllegalArgumentException | DateTimeException failure) {
			throw invalid(Reason.INVALID_PAYLOAD);
		}
	}

	private static ReservationActionEvent evidence(JsonNode envelope, Signal signal, String processId) {
		return new ReservationActionEvent(text(envelope, "eventId"), signal, processId, text(envelope, "holdId"),
				signal == Signal.PHONE_REQUESTED ? null : text(envelope, "requestEventId"),
				Instant.parse(text(envelope, "occurredAt")),
				signal == Signal.PHONE_REQUESTED || signal == Signal.PHONE_REJECTED
						? Instant.parse(text(envelope, "expiresAt")) : null,
				signal == Signal.RESERVATION_CONFIRMED ? positiveId(envelope, "reservationId") : null,
				signal == Signal.RESERVATION_CONFIRMED ? new StartReservationCommand(positiveId(envelope, "professionalId"),
						LocalDate.parse(text(envelope, "date")), LocalTime.parse(text(envelope, "startTime"))) : null,
				signal == Signal.PHONE_REJECTED || signal == Signal.PROCESS_EXPIRED || signal == Signal.PROCESS_INVALID
						? text(envelope, "reason") : null);
	}

	private static Signal signal(String type) {
		return switch (type) {
			case "AdditionalInformationRequested" -> Signal.PHONE_REQUESTED;
			case "AppointmentConfirmed" -> Signal.RESERVATION_CONFIRMED;
			case "AdditionalInformationRejected" -> Signal.PHONE_REJECTED;
			case "AppointmentProcessExpired" -> Signal.PROCESS_EXPIRED;
			case "AppointmentProcessInvalid" -> Signal.PROCESS_INVALID;
			default -> throw invalid(Reason.UNSUPPORTED_EVENT);
		};
	}

	private static void validateSpecificFields(JsonNode envelope, Signal signal) {
		if (signal == Signal.PHONE_REQUESTED && !"PHONE_NUMBER".equals(text(envelope, "requiredField"))) {
			throw invalid(Reason.INVALID_PAYLOAD);
		}
		if (signal == Signal.RESERVATION_CONFIRMED && !"CONFIRMED".equals(text(envelope, "status"))) {
			throw invalid(Reason.INVALID_PAYLOAD);
		}
		if (signal == Signal.PHONE_REQUESTED || signal == Signal.PHONE_REJECTED) {
			// Required presentation text is not functional evidence and is not copied into the core model.
			var message = envelope.get("message");
			if (message == null || !message.isTextual()) { throw invalid(Reason.INVALID_PAYLOAD); }
		}
	}

	private static String text(JsonNode envelope, String field) {
		var value = envelope.get(field);
		if (value == null || !value.isTextual() || value.asString().isBlank()) { throw invalid(Reason.INVALID_PAYLOAD); }
		return value.asString();
	}

	private static long integer(JsonNode envelope, String field) {
		var value = envelope.get(field);
		if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) { throw invalid(Reason.INVALID_PAYLOAD); }
		return value.asLong();
	}

	private static long positiveId(JsonNode envelope, String field) {
		long value = integer(envelope, field);
		if (value <= 0) { throw invalid(Reason.INVALID_PAYLOAD); }
		return value;
	}

	private static InvalidCatedraActionException invalid(Reason reason) { return new InvalidCatedraActionException(reason); }
}
