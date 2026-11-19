package ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.rest.dto;

import java.time.Instant;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CatedraAppointmentHoldResponseTest {

	private static final String JSON = """
			{"holdId":"hold-6c0c2f41","reservationProcessId":"process-a92b47d0","status":"HELD","expiresAt":"2026-09-10T14:44:50Z"}
			""";
	private static final Instant EXPIRY = Instant.parse("2026-09-10T14:44:50Z");
	private final JsonMapper json = JsonMapper.builder().build();

	@Test
	void readsTheOfficialHoldResponseAndPreservesItsDistinctExternalIdentifiers() {
		var response = json.readValue(JSON, CatedraAppointmentHoldResponse.class);
		assertThat(response.holdId()).isEqualTo("hold-6c0c2f41");
		assertThat(response.reservationProcessId()).isEqualTo("process-a92b47d0");
		assertThat(response.status()).isEqualTo("HELD");
		assertThat(response.expiresAt()).isEqualTo(EXPIRY);
		assertThat(json.readTree(json.writeValueAsString(response))).isEqualTo(json.readTree(JSON));
	}

	@Test
	void toleratesCompatibleAdditionalResponseFields() {
		String extended = JSON.replace("\"status\":", "\"futureField\":{\"version\":2},\"status\":");
		assertThat(json.readValue(extended, CatedraAppointmentHoldResponse.class))
				.isEqualTo(json.readValue(JSON, CatedraAppointmentHoldResponse.class));
	}

	@ParameterizedTest
	@ValueSource(strings = {"holdId", "reservationProcessId", "status", "expiresAt"})
	void rejectsMissingOrNullRequiredResponseProperties(String field) {
		var value = (ObjectNode) json.readTree(JSON);
		value.remove(field);
		String missing = json.writeValueAsString(value);
		assertThatThrownBy(() -> json.readValue(missing, CatedraAppointmentHoldResponse.class)).isInstanceOf(JacksonException.class);
		value.putNull(field);
		String explicitNull = json.writeValueAsString(value);
		assertThatThrownBy(() -> json.readValue(explicitNull, CatedraAppointmentHoldResponse.class)).isInstanceOf(JacksonException.class);
	}

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = {" ", "\t"})
	void requiresNonblankExternalIdentifiers(String identifier) {
		assertThatThrownBy(() -> new CatedraAppointmentHoldResponse(identifier, "process", "HELD", EXPIRY))
				.isInstanceOf(IllegalArgumentException.class).hasMessage("Held identifiers must be nonblank");
		assertThatThrownBy(() -> new CatedraAppointmentHoldResponse("hold", identifier, "HELD", EXPIRY))
				.isInstanceOf(IllegalArgumentException.class).hasMessage("Held identifiers must be nonblank");
	}

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = {"PENDING_HOLD", "CONFIRMED", "held", " HELD "})
	void acceptsOnlyTheExactExternalSuccessStatus(String status) {
		assertThatThrownBy(() -> new CatedraAppointmentHoldResponse("hold", "process", status, EXPIRY))
				.isInstanceOf(IllegalArgumentException.class).hasMessage("Successful hold response must have status HELD");
	}

	@ParameterizedTest
	@ValueSource(strings = {"0", "1", "true", "{}", "[]"})
	void numericOrOtherJsonStatusesCannotMasqueradeAsHeld(String value) {
		String invalid = JSON.replace("\"HELD\"", value);
		assertThatThrownBy(() -> json.readValue(invalid, CatedraAppointmentHoldResponse.class)).isInstanceOf(JacksonException.class);
	}

	@Test
	void doesNotImposeAnUndocumentedLengthLimitOrNormalizeOpaqueExternalIds() {
		String hold = " hold:" + "h".repeat(300);
		String process = " process:" + "p".repeat(300);
		var response = new CatedraAppointmentHoldResponse(hold, process, "HELD", EXPIRY);
		assertThat(response.holdId()).isEqualTo(hold);
		assertThat(response.reservationProcessId()).isEqualTo(process);
	}

	@Test
	void preservesNanosecondsAndResolvesAnOffsetToTheSameAbsoluteExpiration() {
		String value = JSON.replace("2026-09-10T14:44:50Z", "2026-09-10T11:44:50.123456789-03:00");
		assertThat(json.readValue(value, CatedraAppointmentHoldResponse.class).expiresAt())
				.isEqualTo(Instant.parse("2026-09-10T14:44:50.123456789Z"));
	}

	@Test
	void neverRecomputesOrExtendsAnAlreadyPastExpiration() {
		var response = new CatedraAppointmentHoldResponse("hold", "process", "HELD", Instant.EPOCH);
		assertThat(response.expiresAt()).isEqualTo(Instant.EPOCH);
	}

	@ParameterizedTest
	@MethodSource("invalidExpirations")
	void rejectsInvalidOrNonInstantExpirationStrings(String expiresAt) {
		String invalid = JSON.replace("2026-09-10T14:44:50Z", expiresAt);
		assertThatThrownBy(() -> json.readValue(invalid, CatedraAppointmentHoldResponse.class)).isInstanceOf(JacksonException.class);
	}

	static Stream<String> invalidExpirations() {
		return Stream.of("", "not-an-instant", "2026-09-10T14:44:50");
	}

	@Test
	void requiresExpirationEvenWhenConstructedWithoutJackson() {
		assertThatThrownBy(() -> new CatedraAppointmentHoldResponse("hold", "process", "HELD", null))
				.isInstanceOf(NullPointerException.class).hasMessage("Hold expiration is required");
	}

	@Test
	void redactsIdentifiersInStringRepresentations() {
		var response = new CatedraAppointmentHoldResponse("private-hold", "private-process", "HELD", EXPIRY);
		assertThat(response.toString()).isEqualTo("CatedraAppointmentHoldResponse[redacted]");
	}
}
