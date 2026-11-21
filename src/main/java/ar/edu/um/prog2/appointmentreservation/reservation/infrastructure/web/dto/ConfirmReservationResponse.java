package ar.edu.um.prog2.appointmentreservation.reservation.infrastructure.web.dto;

import java.time.Instant;
import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonFormat;

import ar.edu.um.prog2.appointmentreservation.reservation.domain.model.ReservationProcessStatus;

/** Minimal public observation after initial confirmation, including progress advanced by asynchronous events. */
public record ConfirmReservationResponse(long id, ReservationProcessStatus status,
		@JsonFormat(shape = JsonFormat.Shape.STRING) Instant expiresAt) {

	public ConfirmReservationResponse {
		if (id <= 0 || status == null || !afterConfirmationStarted(status)) {
			throw new IllegalArgumentException("A positive local ID and initial-confirmation observation are required");
		}
		Objects.requireNonNull(expiresAt, "Confirmation expiration is required");
	}
	private static boolean afterConfirmationStarted(ReservationProcessStatus status) {
		return switch (status) {
			case CONFIRMATION_PENDING, WAITING_FOR_PHONE, PHONE_SUBMISSION_PENDING,
					WAITING_FOR_CONFIRMATION, CONFIRMED, EXPIRED, INVALID -> true;
			case PENDING_HOLD, HELD -> false;
		};
	}
}
