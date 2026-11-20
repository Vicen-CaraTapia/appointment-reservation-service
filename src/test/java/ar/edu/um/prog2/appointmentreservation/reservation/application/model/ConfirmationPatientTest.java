package ar.edu.um.prog2.appointmentreservation.reservation.application.model;

import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConfirmationPatientTest {

	@ParameterizedTest
	@ValueSource(ints = {2, 100})
	void acceptsBothNameLengthBoundaries(int length) {
		var patient = new ConfirmationPatient("a".repeat(length), "b".repeat(length));
		assertThat(patient.firstName()).hasSize(length);
		assertThat(patient.lastName()).hasSize(length);
	}

	@ParameterizedTest
	@MethodSource("invalidNames")
	void rejectsInvalidNamesWithoutIncludingTheirValues(String value) {
		assertThatThrownBy(() -> new ConfirmationPatient(value, "Perez"))
				.isInstanceOf(IllegalArgumentException.class).hasMessage("Patient names must be nonblank and contain 2 to 100 characters");
		assertThatThrownBy(() -> new ConfirmationPatient("Ana", value))
				.isInstanceOf(IllegalArgumentException.class).hasMessage("Patient names must be nonblank and contain 2 to 100 characters");
	}

	static Stream<String> invalidNames() {
		return Stream.of(null, "", "  ", "\t\n", "A", "private-patient-input".repeat(6));
	}

	@Test
	void preservesWhitespaceAndRedactsItsStringRepresentation() {
		var patient = new ConfirmationPatient(" Ana ", " Perez ");
		assertThat(patient.firstName()).isEqualTo(" Ana ");
		assertThat(patient.lastName()).isEqualTo(" Perez ");
		assertThat(patient.toString()).isEqualTo("ConfirmationPatient[redacted]");
	}
}
