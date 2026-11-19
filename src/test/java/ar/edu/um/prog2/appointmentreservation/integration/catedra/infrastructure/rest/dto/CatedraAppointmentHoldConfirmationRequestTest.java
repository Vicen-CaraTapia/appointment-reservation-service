package ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.rest.dto;

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

class CatedraAppointmentHoldConfirmationRequestTest {

	private static final String JSON = """
			{"externalPatientId":"user-42","patientFirstName":"Juan","patientLastName":"Perez"}
			""";
	private final JsonMapper json = JsonMapper.builder().build();

	@Test
	void serializesExactlyTheThreeOfficialRequestFields() {
		var request = new CatedraAppointmentHoldConfirmationRequest("user-42", "Juan", "Perez");
		assertThat(json.readTree(json.writeValueAsString(request))).isEqualTo(json.readTree(JSON));
		assertThat(json.readValue(JSON, CatedraAppointmentHoldConfirmationRequest.class)).isEqualTo(request);
	}

	@ParameterizedTest
	@ValueSource(ints = {1, 100})
	void acceptsPatientIdentifierLengthBoundaries(int length) {
		String id = "p".repeat(length);
		assertThat(new CatedraAppointmentHoldConfirmationRequest(id, "Ana", "Perez").externalPatientId()).isEqualTo(id);
	}

	@ParameterizedTest
	@ValueSource(ints = {2, 100})
	void acceptsBothPatientNameLengthBoundaries(int length) {
		var request = new CatedraAppointmentHoldConfirmationRequest("user-42", "a".repeat(length), "b".repeat(length));
		assertThat(request.patientFirstName()).hasSize(length);
		assertThat(request.patientLastName()).hasSize(length);
	}

	@ParameterizedTest
	@MethodSource("invalidPatientIds")
	void rejectsInvalidPatientIdentityWithoutEchoingInput(String value) {
		assertThatThrownBy(() -> new CatedraAppointmentHoldConfirmationRequest(value, "Ana", "Perez"))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("External patient ID must be nonblank and at most 100 characters");
	}

	static Stream<String> invalidPatientIds() {
		return Stream.of(null, "", " ", "\t\n", "private-patient-id".repeat(7));
	}

	@ParameterizedTest
	@MethodSource("invalidNames")
	void rejectsMissingBlankOrOutOfRangeNamesWithoutEchoingInput(String value) {
		assertThatThrownBy(() -> new CatedraAppointmentHoldConfirmationRequest("user-42", value, "Perez"))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("Patient names must be nonblank and contain 2 to 100 characters");
		assertThatThrownBy(() -> new CatedraAppointmentHoldConfirmationRequest("user-42", "Ana", value))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("Patient names must be nonblank and contain 2 to 100 characters");
	}

	static Stream<String> invalidNames() {
		return Stream.of(null, "", "  ", "\t\n", "A", "a".repeat(101));
	}

	@ParameterizedTest
	@ValueSource(strings = {"externalPatientId", "patientFirstName", "patientLastName"})
	void rejectsMissingAndNullJsonProperties(String field) {
		var value = (ObjectNode) json.readTree(JSON);
		value.remove(field);
		String missing = json.writeValueAsString(value);
		assertThatThrownBy(() -> json.readValue(missing, CatedraAppointmentHoldConfirmationRequest.class))
				.isInstanceOf(JacksonException.class);
		value.putNull(field);
		String explicitNull = json.writeValueAsString(value);
		assertThatThrownBy(() -> json.readValue(explicitNull, CatedraAppointmentHoldConfirmationRequest.class))
				.isInstanceOf(JacksonException.class);
	}

	@Test
	void preservesNamesAndPatientIdentityWithoutNormalizationAndRedactsItsString() {
		var request = new CatedraAppointmentHoldConfirmationRequest(" Patient:42 ", " Ana María ", " Pérez ");
		var serialized = json.readTree(json.writeValueAsString(request));
		assertThat(serialized.get("externalPatientId").asString()).isEqualTo(" Patient:42 ");
		assertThat(serialized.get("patientFirstName").asString()).isEqualTo(" Ana María ");
		assertThat(serialized.get("patientLastName").asString()).isEqualTo(" Pérez ");
		assertThat(request.toString()).isEqualTo("CatedraAppointmentHoldConfirmationRequest[redacted]");
	}
}
