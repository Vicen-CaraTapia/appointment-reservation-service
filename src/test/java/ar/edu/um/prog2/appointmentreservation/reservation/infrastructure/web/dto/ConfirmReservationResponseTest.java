package ar.edu.um.prog2.appointmentreservation.reservation.infrastructure.web.dto;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import ar.edu.um.prog2.appointmentreservation.reservation.domain.model.ReservationProcessStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConfirmReservationResponseTest {

	@ParameterizedTest
	@EnumSource(value = ReservationProcessStatus.class, names = {"CONFIRMATION_PENDING", "WAITING_FOR_PHONE"})
	void representsBothAllowedObservationsWithoutExtendingAnExpiredDeadline(ReservationProcessStatus status) {
		var response = new ConfirmReservationResponse(123, status, Instant.EPOCH);
		assertThat(response.id()).isEqualTo(123);
		assertThat(response.status()).isEqualTo(status);
		assertThat(response.expiresAt()).isEqualTo(Instant.EPOCH);
	}

	@ParameterizedTest
	@ValueSource(longs = {1, Long.MAX_VALUE})
	void preservesThePositiveLocalInt64Identifier(long id) {
		assertThat(new ConfirmReservationResponse(id, ReservationProcessStatus.WAITING_FOR_PHONE, Instant.EPOCH).id())
				.isEqualTo(id);
	}

	@ParameterizedTest
	@ValueSource(longs = {Long.MIN_VALUE, -1, 0})
	void cannotReturnANonpositiveLocalIdentifier(long id) {
		assertThatThrownBy(() -> new ConfirmReservationResponse(id, ReservationProcessStatus.WAITING_FOR_PHONE, Instant.EPOCH))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@ParameterizedTest
	@EnumSource(value = ReservationProcessStatus.class, names = {"CONFIRMATION_PENDING", "WAITING_FOR_PHONE"},
			mode = EnumSource.Mode.EXCLUDE)
	void cannotExposeUnintegratedWorkflowStatesThroughTheInitialConfirmationContract(ReservationProcessStatus status) {
		assertThatThrownBy(() -> new ConfirmReservationResponse(123, status, Instant.EPOCH))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void requiresStatusAndAuthoritativeExpiration() {
		assertThatThrownBy(() -> new ConfirmReservationResponse(123, null, Instant.EPOCH))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new ConfirmReservationResponse(123, ReservationProcessStatus.WAITING_FOR_PHONE, null))
				.isInstanceOf(NullPointerException.class).hasMessage("Confirmation expiration is required");
	}
}
