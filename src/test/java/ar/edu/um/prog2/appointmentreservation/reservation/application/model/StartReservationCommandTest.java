package ar.edu.um.prog2.appointmentreservation.reservation.application.model;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Arrays;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StartReservationCommandTest {

	private static final LocalDate DATE = LocalDate.of(2026, 11, 19);
	private static final LocalTime TIME = LocalTime.of(18, 0);

	@Test
	void selectionContractHasNoCallerSuppliedOwnerPatientOrAdministrativeFields() {
		// This checks the application input surface; HTTP unknown-field rejection belongs to A6.4.
		assertThat(Arrays.stream(StartReservationCommand.class.getRecordComponents()).map(component -> component.getName()))
				.containsExactly("professionalId", "date", "startTime");
	}

	@ParameterizedTest
	@ValueSource(longs = {Long.MIN_VALUE, 0, 202, Long.MAX_VALUE})
	void preservesTheContractualInt64ProfessionalIdAndTheExactSelectedSlot(long professionalId) {
		var selectedTime = TIME.plusNanos(123456000);
		var selection = new StartReservationCommand(professionalId, DATE, selectedTime);
		assertThat(selection.professionalId()).isEqualTo(professionalId);
		assertThat(selection.date()).isEqualTo(DATE);
		assertThat(selection.startTime()).isEqualTo(selectedTime);
	}

	@Test
	void rejectsMissingDateWithoutEchoingTheSelection() {
		assertThatThrownBy(() -> new StartReservationCommand(202, null, TIME))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("Appointment date and start time are required").hasNoCause();
	}

	@Test
	void rejectsMissingStartTime() {
		assertThatThrownBy(() -> new StartReservationCommand(202, DATE, null))
				.isInstanceOf(IllegalArgumentException.class).hasNoCause();
	}
}
