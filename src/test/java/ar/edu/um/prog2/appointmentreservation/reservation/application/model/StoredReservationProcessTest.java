package ar.edu.um.prog2.appointmentreservation.reservation.application.model;

import java.time.LocalDate;
import java.time.LocalTime;

import org.junit.jupiter.api.Test;

import ar.edu.um.prog2.appointmentreservation.reservation.domain.model.ReservationProcessStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StoredReservationProcessTest {

	private static final StartReservationCommand SELECTION =
			new StartReservationCommand(202, LocalDate.of(2026, 11, 19), LocalTime.of(18, 0));

	@Test
	void representsAnOwnedPendingObservationWithoutExposingPatientDataInItsString() {
		var value = new StoredReservationProcess(1, 0, ReservationProcessStatus.PENDING_HOLD,
				SELECTION, "user-42", null, null, null);
		assertThat(value.id()).isEqualTo(1);
		assertThat(value.version()).isZero();
		assertThat(value.hold()).isNull();
		assertThat(value.toString()).isEqualTo("StoredReservationProcess[redacted]");
	}

	@Test
	void rejectsIncompletePersistedIdentityVersionOrSelection() {
		assertThatThrownBy(() -> observation(0, 0, ReservationProcessStatus.HELD, SELECTION, "user-42"))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> observation(1, -1, ReservationProcessStatus.HELD, SELECTION, "user-42"))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> observation(1, 0, null, SELECTION, "user-42"))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> observation(1, 0, ReservationProcessStatus.HELD, null, "user-42"))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> observation(1, 0, ReservationProcessStatus.HELD, SELECTION, null))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> observation(1, 0, ReservationProcessStatus.HELD, SELECTION, " "))
				.isInstanceOf(IllegalArgumentException.class);
	}

	private StoredReservationProcess observation(long id, long version, ReservationProcessStatus status,
			StartReservationCommand selection, String patientId) {
		return new StoredReservationProcess(id, version, status, selection, patientId, null, null, null);
	}
}
