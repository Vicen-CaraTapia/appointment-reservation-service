package ar.edu.um.prog2.appointmentreservation.reservation.application.model;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AppointmentHoldTest {

	@Test
	void preservesOpaqueIdentifiersAndAuthoritativeExpirationWithoutInventingAnotherTtl() {
		String holdId = "opaque-hold-" + "x".repeat(300);
		String externalProcessId = "opaque-process-" + "y".repeat(300);
		// Structural validity is separate from checking whether a remote hold is still usable.
		Instant expiry = Instant.parse("2000-01-01T00:00:00.123456789Z");
		var hold = new AppointmentHold(holdId, externalProcessId, expiry);
		var process = new HeldReservationProcess(42, hold);
		assertThat(process.id()).isEqualTo(42);
		assertThat(process.hold().holdId()).isEqualTo(holdId);
		assertThat(process.hold().reservationProcessId()).isEqualTo(externalProcessId);
		assertThat(process.hold().expiresAt()).isEqualTo(expiry);
		assertThat(hold.toString()).isEqualTo("AppointmentHold[redacted]");
		assertThat(process.toString()).doesNotContain(holdId, externalProcessId);
	}

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = {" ", "\t", "\n"})
	void rejectsIncompleteExternalIdentityWithoutEchoingRemoteValues(String invalid) {
		assertThatThrownBy(() -> new AppointmentHold(invalid, "private-process", Instant.EPOCH))
				.isInstanceOf(IllegalArgumentException.class).hasMessageNotContaining("private-process").hasNoCause();
		assertThatThrownBy(() -> new AppointmentHold("private-hold", invalid, Instant.EPOCH))
				.isInstanceOf(IllegalArgumentException.class).hasMessageNotContaining("private-hold").hasNoCause();
	}

	@Test
	void requiresARemoteExpiration() {
		assertThatThrownBy(() -> new AppointmentHold("private-hold", "private-process", null))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("A complete successful appointment hold is required").hasNoCause();
	}

	@ParameterizedTest
	@ValueSource(longs = {0, -1, Long.MIN_VALUE})
	void requiresAPersistedLocalIdentityIndependentFromTheRemoteIdentifiers(long id) {
		assertThatThrownBy(() -> new HeldReservationProcess(id, new AppointmentHold("h", "p", Instant.EPOCH)))
				.isInstanceOf(IllegalArgumentException.class).hasNoCause();
	}

	@Test
	void cannotRepresentAHeldProcessWithoutItsHold() {
		assertThatThrownBy(() -> new HeldReservationProcess(42, null))
				.isInstanceOf(IllegalArgumentException.class).hasNoCause();
	}
}
