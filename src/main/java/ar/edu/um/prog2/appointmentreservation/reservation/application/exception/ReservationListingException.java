package ar.edu.um.prog2.appointmentreservation.reservation.application.exception;

import java.util.Objects;

public final class ReservationListingException extends RuntimeException {
	public enum Reason { INVALID_DATE_RANGE, INTEGRATION_NOT_FOUND, UNAUTHORIZED, FORBIDDEN, NOT_FOUND,
		CONFLICT, REQUEST_REJECTED, TIMEOUT, UNAVAILABLE, INVALID_RESPONSE }
	private final Reason reason;
	public ReservationListingException(Reason reason) {
		super("Reservation listing outcome: " + Objects.requireNonNull(reason)); this.reason = reason;
	}
	public Reason reason() { return reason; }
}
