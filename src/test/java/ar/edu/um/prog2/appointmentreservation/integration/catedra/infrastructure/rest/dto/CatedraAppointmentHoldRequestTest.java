package ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.rest.dto;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CatedraAppointmentHoldRequestTest {

	private static final LocalDate DATE = LocalDate.of(2026, 9, 14);
	private static final LocalTime TIME = LocalTime.of(9, 30);
	private static final String JSON = """
			{"professionalId":15,"date":"2026-09-14","startTime":"09:30:00","externalPatientId":"user-42"}
			""";
	private final JsonMapper json = JsonMapper.builder().build();

	@Test
	void serializesExactlyTheFourContractFieldsWithLocalDatesAndTimes() {
		var request = new CatedraAppointmentHoldRequest(15, DATE, TIME, "user-42");
		assertThat(json.readTree(json.writeValueAsString(request))).isEqualTo(json.readTree(JSON));
		assertThat(json.readValue(JSON, CatedraAppointmentHoldRequest.class)).isEqualTo(request);
	}

	@ParameterizedTest
	@ValueSource(longs = {Long.MIN_VALUE, 0, Long.MAX_VALUE})
	void preservesTheFullSignedInt64IdentifierWithoutAddingPositivityRules(long id) {
		var request = new CatedraAppointmentHoldRequest(id, DATE, TIME, "user-42");
		String serialized = json.writeValueAsString(request);
		assertThat(json.readTree(serialized).get("professionalId").isIntegralNumber()).isTrue();
		assertThat(json.readValue(serialized, CatedraAppointmentHoldRequest.class)).isEqualTo(request);
	}

	@ParameterizedTest
	@ValueSource(ints = {1, 100})
	void acceptsTheContractualPatientIdentifierLengthBoundaries(int length) {
		String patientId = "p".repeat(length);
		assertThat(new CatedraAppointmentHoldRequest(15, DATE, TIME, patientId).externalPatientId()).isEqualTo(patientId);
	}

	@Test
	void preservesPatientIdentityAndTimePrecisionWithoutNormalization() {
		var request = new CatedraAppointmentHoldRequest(15, DATE,
				LocalTime.of(9, 30, 15, 123456789), " Patient:42 ");
		var value = json.readTree(json.writeValueAsString(request));
		assertThat(value.get("startTime").asString()).isEqualTo("09:30:15.123456789");
		assertThat(value.get("externalPatientId").asString()).isEqualTo(" Patient:42 ");
	}

	@ParameterizedTest
	@MethodSource("invalidPatientIds")
	void rejectsMissingBlankAndOverlongPatientIdentifiersWithoutEchoingTheirValues(String patientId) {
		assertThatThrownBy(() -> new CatedraAppointmentHoldRequest(15, DATE, TIME, patientId))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("External patient ID must be nonblank and at most 100 characters");
	}

	static Stream<String> invalidPatientIds() {
		return Stream.of(null, "", " ", "\t", "p".repeat(101));
	}

	@Test
	void rejectsMissingAppointmentDateOrTime() {
		assertThatThrownBy(() -> new CatedraAppointmentHoldRequest(15, null, TIME, "user-42"))
				.isInstanceOf(NullPointerException.class).hasMessage("Hold appointment date is required");
		assertThatThrownBy(() -> new CatedraAppointmentHoldRequest(15, DATE, null, "user-42"))
				.isInstanceOf(NullPointerException.class).hasMessage("Hold appointment start time is required");
	}

	@ParameterizedTest
	@ValueSource(strings = {"professionalId", "date", "startTime", "externalPatientId"})
	void rejectsMissingAndNullRequiredJsonProperties(String field) {
		var value = (ObjectNode) json.readTree(JSON);
		value.remove(field);
		String missing = json.writeValueAsString(value);
		assertThatThrownBy(() -> json.readValue(missing, CatedraAppointmentHoldRequest.class)).isInstanceOf(JacksonException.class);
		value.putNull(field);
		String explicitNull = json.writeValueAsString(value);
		assertThatThrownBy(() -> json.readValue(explicitNull, CatedraAppointmentHoldRequest.class)).isInstanceOf(JacksonException.class);
	}

	@Test
	void stringRepresentationDoesNotExposeThePatientIdentifier() {
		var request = new CatedraAppointmentHoldRequest(15, DATE, TIME, "test-only-private-patient");
		assertThat(request.toString()).isEqualTo("CatedraAppointmentHoldRequest[redacted]");
	}
}
