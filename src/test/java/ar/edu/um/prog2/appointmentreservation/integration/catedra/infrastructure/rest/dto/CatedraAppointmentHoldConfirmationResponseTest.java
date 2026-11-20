package ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.rest.dto;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CatedraAppointmentHoldConfirmationResponseTest {

	private static final String JSON = """
			{"reservationProcessId":"process-a92b47d0","status":"WAITING_FOR_PHONE","expiresAt":"2026-09-10T14:44:50Z"}
			""";
	private static final Instant EXPIRY = Instant.parse("2026-09-10T14:44:50Z");
	private final JsonMapper json = JsonMapper.builder().build();

	@Test
	void readsAndWritesTheOfficialInitialAcceptance() {
		var response = json.readValue(JSON, CatedraAppointmentHoldConfirmationResponse.class);
		assertThat(response.reservationProcessId()).isEqualTo("process-a92b47d0");
		assertThat(response.status()).isEqualTo("WAITING_FOR_PHONE");
		assertThat(response.expiresAt()).isEqualTo(EXPIRY);
		assertThat(json.readTree(json.writeValueAsString(response))).isEqualTo(json.readTree(JSON));
	}

	@Test
	void toleratesCompatibleAdditionalResponseFields() {
		String extended = JSON.replace("\"status\":", "\"futureField\":{\"version\":2},\"status\":");
		assertThat(json.readValue(extended, CatedraAppointmentHoldConfirmationResponse.class))
				.isEqualTo(json.readValue(JSON, CatedraAppointmentHoldConfirmationResponse.class));
	}

	@ParameterizedTest
	@ValueSource(strings = {"reservationProcessId", "status", "expiresAt"})
	void rejectsMissingOrNullRequiredResponseProperties(String field) {
		var value = (ObjectNode) json.readTree(JSON);
		value.remove(field);
		String missing = json.writeValueAsString(value);
		assertThatThrownBy(() -> json.readValue(missing, CatedraAppointmentHoldConfirmationResponse.class))
				.isInstanceOf(JacksonException.class);
		value.putNull(field);
		String explicitNull = json.writeValueAsString(value);
		assertThatThrownBy(() -> json.readValue(explicitNull, CatedraAppointmentHoldConfirmationResponse.class))
				.isInstanceOf(JacksonException.class);
	}

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = {" ", "\t\n"})
	void requiresANonblankExternalProcessIdentifier(String id) {
		assertThatThrownBy(() -> new CatedraAppointmentHoldConfirmationResponse(id, "WAITING_FOR_PHONE", EXPIRY))
				.isInstanceOf(IllegalArgumentException.class).hasMessage("Initial confirmation process ID must be nonblank");
	}

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = {"HELD", "PHONE_PENDING", "CONFIRMED", "waiting_for_phone", " WAITING_FOR_PHONE "})
	void requiresTheExactInitialAcceptanceStatus(String status) {
		assertThatThrownBy(() -> new CatedraAppointmentHoldConfirmationResponse("process", status, EXPIRY))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("Initial confirmation response must have status WAITING_FOR_PHONE");
	}

	@Test
	void preservesOpaqueProcessIdentifiersWithoutInventingALengthLimit() {
		String id = " process/opaque:" + "p".repeat(300);
		assertThat(new CatedraAppointmentHoldConfirmationResponse(id, "WAITING_FOR_PHONE", EXPIRY).reservationProcessId())
				.isEqualTo(id);
	}

	@Test
	void preservesNanosecondsAndParsesAnExplicitOffsetWithoutExtendingExpiration() {
		String precise = JSON.replace("2026-09-10T14:44:50Z", "2026-09-10T11:44:50.123456789-03:00");
		assertThat(json.readValue(precise, CatedraAppointmentHoldConfirmationResponse.class).expiresAt())
				.isEqualTo(Instant.parse("2026-09-10T14:44:50.123456789Z"));
		assertThat(new CatedraAppointmentHoldConfirmationResponse("process", "WAITING_FOR_PHONE", Instant.EPOCH).expiresAt())
				.isEqualTo(Instant.EPOCH);
	}

	@ParameterizedTest
	@ValueSource(strings = {"", "not-an-instant", "2026-09-10T14:44:50"})
	void rejectsMalformedOrNonAbsoluteExpiration(String expiresAt) {
		String invalid = JSON.replace("2026-09-10T14:44:50Z", expiresAt);
		assertThatThrownBy(() -> json.readValue(invalid, CatedraAppointmentHoldConfirmationResponse.class))
				.isInstanceOf(JacksonException.class);
	}

	@Test
	void requiresExpirationAndRedactsItsString() {
		assertThatThrownBy(() -> new CatedraAppointmentHoldConfirmationResponse("process", "WAITING_FOR_PHONE", null))
				.isInstanceOf(NullPointerException.class).hasMessage("Initial confirmation expiration is required");
		assertThat(new CatedraAppointmentHoldConfirmationResponse("private-process", "WAITING_FOR_PHONE", EXPIRY).toString())
				.isEqualTo("CatedraAppointmentHoldConfirmationResponse[redacted]");
	}
}
