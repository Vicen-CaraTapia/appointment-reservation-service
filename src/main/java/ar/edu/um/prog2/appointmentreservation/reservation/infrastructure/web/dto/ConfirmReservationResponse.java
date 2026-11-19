package ar.edu.um.prog2.appointmentreservation.reservation.infrastructure.web.dto;

import java.time.Instant;
import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonFormat;

import ar.edu.um.prog2.appointmentreservation.reservation.domain.model.ReservationProcessStatus;

/** Minimal public observation of initial confirmation, without patient or technical identity. */
public record ConfirmReservationResponse(long id, ReservationProcessStatus status,
		@JsonFormat(shape = JsonFormat.Shape.STRING) Instant expiresAt) {

	public ConfirmReservationResponse {
		if (id <= 0 || (status != ReservationProcessStatus.CONFIRMATION_PENDING
				&& status != ReservationProcessStatus.WAITING_FOR_PHONE)) {
			throw new IllegalArgumentException("A positive local ID and initial-confirmation observation are required");
		}
		Objects.requireNonNull(expiresAt, "Confirmation expiration is required");
	}
}
