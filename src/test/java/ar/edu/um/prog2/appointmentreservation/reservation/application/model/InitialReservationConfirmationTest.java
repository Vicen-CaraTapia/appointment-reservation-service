package ar.edu.um.prog2.appointmentreservation.reservation.application.model;

import java.time.Instant;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InitialReservationConfirmationTest {

	@ParameterizedTest
	@ValueSource(ints = {2, 100})
	void acceptsContractualNameBoundariesWithoutChangingRemoteIdentityOrExpiry(int length) {
		var expires = Instant.parse("2026-11-19T12:00:00.123456789Z");
		var input = new InitialReservationConfirmation("process/opaque", "a".repeat(length), "b".repeat(length), expires);
		assertThat(input.reservationProcessId()).isEqualTo("process/opaque");
		assertThat(input.patientFirstName()).hasSize(length);
		assertThat(input.patientLastName()).hasSize(length);
		assertThat(input.expiresAt()).isEqualTo(expires);
		assertThat(input.toString()).isEqualTo("InitialReservationConfirmation[redacted]");
	}

	@ParameterizedTest
	@MethodSource("invalidNames")
	void rejectsInvalidPatientNamesWithoutIncludingTheirValues(String name) {
		assertThatThrownBy(() -> new InitialReservationConfirmation("process", name, "Perez", Instant.EPOCH))
				.isInstanceOf(IllegalArgumentException.class).hasMessageNotContaining("private-patient-input");
		assertThatThrownBy(() -> new InitialReservationConfirmation("process", "Ana", name, Instant.EPOCH))
				.isInstanceOf(IllegalArgumentException.class).hasMessageNotContaining("private-patient-input");
	}

	static Stream<Arguments> invalidNames() {
		return Stream.of(null, "", "  ", "\t\n", "A", "private-patient-input".repeat(6)).map(Arguments::of);
	}

	@Test
	void preservesPatientWhitespaceAndRejectsMissingRemoteAcceptanceDetails() {
		var input = new InitialReservationConfirmation("process", " Ana ", " Perez ", Instant.EPOCH);
		assertThat(input.patientFirstName()).isEqualTo(" Ana ");
		assertThat(input.patientLastName()).isEqualTo(" Perez ");
		assertThatThrownBy(() -> new InitialReservationConfirmation(null, "Ana", "Perez", Instant.EPOCH))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new InitialReservationConfirmation(" ", "Ana", "Perez", Instant.EPOCH))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new InitialReservationConfirmation("process", "Ana", "Perez", null))
				.isInstanceOf(IllegalArgumentException.class);
	}
}
