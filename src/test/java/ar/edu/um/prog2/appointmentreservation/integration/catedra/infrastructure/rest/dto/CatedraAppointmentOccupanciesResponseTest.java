package ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.rest.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

import ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.rest.dto.CatedraAppointmentOccupanciesResponse.Occupancy;
import ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.rest.dto.CatedraAppointmentOccupanciesResponse.Status;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CatedraAppointmentOccupanciesResponseTest {

	private final JsonMapper json = JsonMapper.builder().build();
	private static final LocalDate DATE = LocalDate.of(2026, 9, 14);
	private static final String RESPONSE = """
			{
			  "professionalId": 15,
			  "from": "2026-09-14",
			  "to": "2026-09-14",
			  "occupancies": [
			    {"date":"2026-09-14","startTime":"09:00:00","status":"CONFIRMED","expiresAt":null},
			    {"date":"2026-09-14","startTime":"09:30:00","status":"HELD","expiresAt":"2026-09-10T14:44:50Z"}
			  ]
			}
			""";

	@Test
	void readsTheOfficialResponseWithoutLosingDatesTimesOrExpiration() {
		var response = read(RESPONSE);
		assertThat(response.professionalId()).isEqualTo(15);
		assertThat(response.from()).isEqualTo(DATE);
		assertThat(response.to()).isEqualTo(DATE);
		assertThat(response.occupancies()).containsExactly(
				new Occupancy(DATE, LocalTime.of(9, 0), Status.CONFIRMED, null),
				new Occupancy(DATE, LocalTime.of(9, 30), Status.HELD, Instant.parse("2026-09-10T14:44:50Z")));
	}

	@Test
	void acceptsAnEmptyListAndAMultipleDayRange() {
		var response = read("""
				{"professionalId":15,"from":"2026-09-14","to":"2026-09-16","occupancies":[]}
				""");
		assertThat(response.occupancies()).isEmpty();
		assertThat(response.to()).isEqualTo(DATE.plusDays(2));
	}

	@ParameterizedTest
	@ValueSource(strings = {"CONFIRMED", "HELD"})
	void acceptsAnOmittedExpiration(String status) {
		var response = read("""
				{"professionalId":15,"from":"2026-09-14","to":"2026-09-14",
				 "occupancies":[{"date":"2026-09-14","startTime":"09:00:00","status":"%s"}]}
				""".formatted(status));
		assertThat(response.occupancies().getFirst().expiresAt()).isNull();
	}

	@Test
	void toleratesAdditionalFieldsAtBothResponseAndOccupancyLevels() {
		var extended = RESPONSE.replace("\"professionalId\": 15,", "\"professionalId\": 15, \"futureField\": {\"v\":1},")
				.replace("\"status\":\"HELD\"", "\"status\":\"HELD\",\"futureOccupancyField\":true");
		assertThat(read(extended)).isEqualTo(read(RESPONSE));
	}

	@Test
	void preservesSigned64BitProfessionalIdentifiers() {
		assertThat(read(RESPONSE.replace("\"professionalId\": 15", "\"professionalId\": 9223372036854775807"))
				.professionalId()).isEqualTo(Long.MAX_VALUE);
		assertThat(read(RESPONSE.replace("\"professionalId\": 15", "\"professionalId\": -9223372036854775808"))
				.professionalId()).isEqualTo(Long.MIN_VALUE);
	}

	@Test
	void preservesTimePrecisionAndNormalizesExpirationOffsetsToAnInstant() {
		var response = read(RESPONSE.replace("09:30:00", "09:30:00.123456789")
				.replace("2026-09-10T14:44:50Z", "2026-09-10T11:44:50-03:00"));
		assertThat(response.occupancies().get(1).startTime()).isEqualTo(LocalTime.of(9, 30, 0, 123456789));
		assertThat(response.occupancies().get(1).expiresAt()).isEqualTo(Instant.parse("2026-09-10T14:44:50Z"));
	}

	@Test
	void takesAnImmutableCopyOfOccupancies() {
		var source = new ArrayList<>(read(RESPONSE).occupancies());
		var response = new CatedraAppointmentOccupanciesResponse(15, DATE, DATE, source);
		source.clear();
		assertThat(response.occupancies()).hasSize(2);
		assertThatThrownBy(() -> response.occupancies().clear()).isInstanceOf(UnsupportedOperationException.class);
	}

	@ParameterizedTest
	@ValueSource(strings = {"CANCELLED", "held", ""})
	void rejectsUnrecognizedStatuses(String status) {
		assertThatThrownBy(() -> read(RESPONSE.replace("\"HELD\"", "\"" + status + "\"")))
				.isInstanceOf(RuntimeException.class);
	}

	@ParameterizedTest
	@ValueSource(strings = {"2026-02-30", "14/09/2026"})
	void rejectsInvalidDates(String date) {
		assertThatThrownBy(() -> read(RESPONSE.replace("2026-09-14", date)))
				.isInstanceOf(RuntimeException.class);
	}

	private CatedraAppointmentOccupanciesResponse read(String value) {
		return json.readValue(value, CatedraAppointmentOccupanciesResponse.class);
	}
}
