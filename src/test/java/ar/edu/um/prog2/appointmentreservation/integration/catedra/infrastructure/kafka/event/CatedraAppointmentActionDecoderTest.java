package ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.kafka.event;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.kafka.exception.InvalidCatedraActionException;
import ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.kafka.exception.InvalidCatedraActionException.Reason;
import ar.edu.um.prog2.appointmentreservation.reservation.application.model.StartReservationCommand;
import ar.edu.um.prog2.appointmentreservation.reservation.domain.model.AsynchronousReservationTransitions.Signal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

@ExtendWith(OutputCaptureExtension.class)
class CatedraAppointmentActionDecoderTest {
	private static final String REQUEST = "AdditionalInformationRequested";
	private static final String CONFIRMED = "AppointmentConfirmed";
	private static final String REJECTED = "AdditionalInformationRejected";
	private static final String EXPIRED = "AppointmentProcessExpired";
	private static final String INVALID = "AppointmentProcessInvalid";
	private static final List<String> TYPES = List.of(REQUEST, CONFIRMED, REJECTED, EXPIRED, INVALID);
	private static final JsonMapper JSON = new JsonMapper();
	private final CatedraAppointmentActionDecoder decoder = new CatedraAppointmentActionDecoder("test-project");

	@ParameterizedTest
	@CsvSource({"AdditionalInformationRequested,PHONE_REQUESTED", "AppointmentConfirmed,RESERVATION_CONFIRMED",
			"AdditionalInformationRejected,PHONE_REJECTED", "AppointmentProcessExpired,PROCESS_EXPIRED",
			"AppointmentProcessInvalid,PROCESS_INVALID"})
	void mapsOfficialV1FieldsToExistingApplicationEvidence(String type, Signal signal) {
		var result = decoder.decode("process-opaque", valid(type).toString());
		assertThat(result.signal()).isEqualTo(signal);
		assertThat(result.eventId()).isEqualTo("event-opaque");
		assertThat(result.externalProcessId()).isEqualTo("process-opaque");
		assertThat(result.holdId()).isEqualTo("hold-opaque");
		assertThat(result.occurredAt()).isEqualTo(Instant.parse("2030-01-03T12:00:00.123456789Z"));
		assertThat(result.requestEventId()).isEqualTo(type.equals(REQUEST) ? null : "request-original");
		assertThat(result.expiresAt()).isEqualTo(type.equals(REQUEST) || type.equals(REJECTED)
				? Instant.parse("2030-01-03T12:05:00.987654321Z") : null);
		assertThat(result.externalReservationId()).isEqualTo(type.equals(CONFIRMED) ? Long.MAX_VALUE : null);
		assertThat(result.confirmedSelection()).isEqualTo(type.equals(CONFIRMED)
				? new StartReservationCommand(202, LocalDate.of(2030, 1, 3), LocalTime.of(18, 0)) : null);
		assertThat(result.reason()).isEqualTo(switch (type) {
			case REJECTED -> "INVALID_PHONE_NUMBER";
			case EXPIRED -> "HOLD_EXPIRED";
			case INVALID -> "REQUEST_EVENT_MISMATCH";
			default -> null;
		});
	}

	@ParameterizedTest
	@MethodSource("types")
	void ignoresCompatibleExtraFieldsWithoutTrustingOwnerOrTechnicalCredentials(String type) {
		var input = valid(type);
		var original = decoder.decode("process-opaque", input.toString());
		input.set("futureMetadata", JSON.readTree("{\"nested\":[true,null,42]}"));
		input.put("ownerUserId", 999);
		input.put("technicalJwt", "synthetic-private-token");
		assertThat(decoder.decode("process-opaque", input.toString())).isEqualTo(original);
	}

	@ParameterizedTest
	@MethodSource("requiredFields")
	void rejectsMissingNullAndWrongTypedRequiredFields(String type, String field) {
		var input = valid(type);
		input.remove(field);
		assertInvalid(input.toString(), Reason.INVALID_PAYLOAD);
		input.putNull(field);
		assertInvalid(input.toString(), Reason.INVALID_PAYLOAD);
		input.set(field, JSON.readTree("[]"));
		assertInvalid(input.toString(), Reason.INVALID_PAYLOAD);
	}

	@ParameterizedTest
	@ValueSource(strings = {"eventId", "reservationProcessId", "holdId", "occurredAt", "expiresAt", "requiredField"})
	void rejectsBlankRequiredRequestFields(String field) {
		var input = valid(REQUEST).put(field, " ");
		assertInvalid(input.toString(), Reason.INVALID_PAYLOAD);
	}

	@ParameterizedTest
	@NullSource
	@ValueSource(strings = {"", " ", "null", "[]", "true", "42", "\"private-input\"", "{", "{} {}"})
	void rejectsInvalidJsonAndNonObjectRoots(String payload) { assertInvalid(payload, Reason.INVALID_PAYLOAD); }

	@Test
	void rejectsAmbiguousDuplicateFieldsAndTrailingDocuments() {
		String request = valid(REQUEST).toString();
		assertInvalid(request.replace("\"schemaVersion\":1", "\"schemaVersion\":2,\"schemaVersion\":1"), Reason.INVALID_PAYLOAD);
		assertInvalid(request + " {}", Reason.INVALID_PAYLOAD);
	}

	@ParameterizedTest
	@ValueSource(strings = {"0", "2", "-1"})
	void distinguishesUnsupportedIntegralVersions(String version) {
		var input = valid(REQUEST);
		input.set("schemaVersion", JSON.readTree(version));
		assertInvalid(input.toString(), Reason.UNSUPPORTED_VERSION);
	}

	@ParameterizedTest
	@ValueSource(strings = {"\"1\"", "1.0", "true", "9223372036854775808"})
	void doesNotCoerceOrOverflowNumericFields(String literal) {
		for (String field : List.of("schemaVersion", "reservationId", "professionalId")) {
			var input = valid(CONFIRMED);
			input.set(field, JSON.readTree(literal));
			assertInvalid(input.toString(), Reason.INVALID_PAYLOAD);
		}
	}

	@ParameterizedTest
	@ValueSource(strings = {"AdditionalInformationSubmitted", "CatalogUpdated", "AppointmentCancelled", "UnknownEvent"})
	void distinguishesUnsupportedEventTypes(String type) {
		assertInvalid(valid(REQUEST).put("eventType", type).toString(), Reason.UNSUPPORTED_EVENT);
	}

	@ParameterizedTest
	@NullSource
	@ValueSource(strings = {"", " ", "other-process", " process-opaque"})
	void rejectsMissingOrMismatchedMessageKeys(String key) {
		assertThatThrownBy(() -> decoder.decode(key, valid(REQUEST).toString()))
				.isInstanceOfSatisfying(InvalidCatedraActionException.class,
						error -> assertThat(error.reason()).isEqualTo(Reason.KEY_MISMATCH));
	}

	@ParameterizedTest
	@ValueSource(strings = {CONFIRMED, REJECTED, EXPIRED, INVALID})
	void enforcesTheConfiguredTechnicalGroupOnlyWhereTheContractDefinesIt(String type) {
		assertInvalid(valid(type).put("groupId", "other-project").toString(), Reason.GROUP_MISMATCH);
		assertInvalid(valid(type).put("groupId", " ").toString(), Reason.INVALID_PAYLOAD);
	}

	@Test
	void aRequestDoesNotRequireGroupAndPresentationTextDoesNotDetermineBehavior() {
		var input = valid(REQUEST);
		var original = decoder.decode("process-opaque", input.toString());
		input.put("message", "CONFIRMED; arbitrary presentation text");
		input.put("groupId", "compatible-extra-field");
		assertThat(decoder.decode("process-opaque", input.toString())).isEqualTo(original);
		input.put("requiredField", "EMAIL");
		assertInvalid(input.toString(), Reason.INVALID_PAYLOAD);
	}

	@Test
	void unknownWellFormedReasonsRemainEvidenceForTheExistingConflictPolicy() {
		var result = decoder.decode("process-opaque", valid(INVALID).put("reason", "FUTURE_REASON").toString());
		assertThat(result.reason()).isEqualTo("FUTURE_REASON");
		assertThat(result.hasSupportedReason()).isFalse();
		assertInvalid(valid(INVALID).put("reason", "private human explanation").toString(), Reason.INVALID_PAYLOAD);
	}

	@ParameterizedTest
	@CsvSource({"status,HELD", "date,2030-02-30", "startTime,25:00", "occurredAt,not-an-instant",
			"reservationId,0", "professionalId,-1"})
	void rejectsInvalidConfirmationValues(String field, String value) {
		var input = valid(CONFIRMED);
		if (field.endsWith("Id")) { input.put(field, Long.parseLong(value)); } else { input.put(field, value); }
		assertInvalid(input.toString(), Reason.INVALID_PAYLOAD);
	}

	@Test
	void parserFailuresDoNotExposePayloadThroughMessagesCausesStackTracesOrLogs(CapturedOutput output) {
		String secret = "synthetic-private-token";
		var error = catchThrowableOfType(InvalidCatedraActionException.class,
				() -> decoder.decode("process-opaque", "{\"" + secret + "\": ["));
		assertThat(error).hasMessage("Invalid catedra appointment action: INVALID_PAYLOAD").hasNoCause();
		var trace = new StringWriter();
		error.printStackTrace(new PrintWriter(trace));
		assertThat(trace.toString()).doesNotContain(secret);
		assertThat(output.getAll()).doesNotContain(secret);
		assertThatThrownBy(() -> new CatedraAppointmentActionDecoder(" "))
				.isInstanceOf(IllegalArgumentException.class).hasMessage("Expected technical group is required");
		assertThatThrownBy(() -> new CatedraAppointmentActionDecoder(null)).isInstanceOf(IllegalArgumentException.class);
	}

	private void assertInvalid(String payload, Reason reason) {
		assertThatThrownBy(() -> decoder.decode("process-opaque", payload))
				.isInstanceOfSatisfying(InvalidCatedraActionException.class, error -> assertThat(error.reason()).isEqualTo(reason))
				.hasNoCause();
	}

	private static Stream<String> types() { return TYPES.stream(); }

	private static Stream<Arguments> requiredFields() {
		return TYPES.stream().flatMap(type -> {
			var fields = new java.util.ArrayList<>(List.of("eventId", "eventType", "schemaVersion", "reservationProcessId", "holdId", "occurredAt"));
			if (!type.equals(REQUEST)) { fields.addAll(List.of("groupId", "requestEventId")); }
			if (type.equals(REQUEST)) { fields.addAll(List.of("requiredField", "message", "expiresAt")); }
			if (type.equals(REJECTED)) { fields.addAll(List.of("reason", "message", "expiresAt")); }
			if (type.equals(EXPIRED) || type.equals(INVALID)) { fields.add("reason"); }
			if (type.equals(CONFIRMED)) { fields.addAll(List.of("reservationId", "professionalId", "date", "startTime", "status")); }
			return fields.stream().map(field -> Arguments.of(type, field));
		});
	}

	private static ObjectNode valid(String type) {
		var input = (ObjectNode) JSON.readTree("""
				{"eventId":"event-opaque","schemaVersion":1,"reservationProcessId":"process-opaque",
				 "holdId":"hold-opaque","occurredAt":"2030-01-03T12:00:00.123456789Z"}
				""");
		input.put("eventType", type);
		if (!type.equals(REQUEST)) { input.put("groupId", "test-project").put("requestEventId", "request-original"); }
		switch (type) {
			case REQUEST -> input.put("requiredField", "PHONE_NUMBER").put("message", "Presentation only")
					.put("expiresAt", "2030-01-03T12:05:00.987654321Z");
			case CONFIRMED -> input.put("reservationId", Long.MAX_VALUE).put("professionalId", 202)
					.put("date", "2030-01-03").put("startTime", "18:00").put("status", "CONFIRMED");
			case REJECTED -> input.put("reason", "INVALID_PHONE_NUMBER").put("message", "Presentation only")
					.put("expiresAt", "2030-01-03T12:05:00.987654321Z");
			case EXPIRED -> input.put("reason", "HOLD_EXPIRED");
			case INVALID -> input.put("reason", "REQUEST_EVENT_MISMATCH");
			default -> throw new IllegalArgumentException("Unsupported test fixture");
		}
		return input;
	}
}
