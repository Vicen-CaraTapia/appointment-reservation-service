package ar.edu.um.prog2.appointmentreservation.reservation.application.exception;

import java.util.Objects;

public final class ReservationCancellationException extends RuntimeException {
	public enum Reason {
		RESERVATION_NOT_FOUND, RESERVATION_OWNER_MISMATCH, RESERVATION_NOT_CANCELLABLE,
		INTEGRATION_NOT_FOUND, UNAUTHORIZED, FORBIDDEN, NOT_FOUND, CONFLICT, REQUEST_REJECTED,
		TIMEOUT, UNAVAILABLE, REMOTE_FAILURE, INVALID_RESPONSE, OUTCOME_UNKNOWN
	}
	private final Reason reason;

	public ReservationCancellationException(Reason reason) {
		super("Reservation cancellation outcome: " + Objects.requireNonNull(reason));
		this.reason = reason;
	}

	public Reason reason() { return reason; }
}
