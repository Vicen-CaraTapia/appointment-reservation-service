package ar.edu.um.prog2.appointmentreservation.reservation.application.exception;

import java.util.Objects;

public final class OwnedReservationQueryException extends RuntimeException {
	public enum Reason { INCOMPLETE_RESULT, INVALID_RESPONSE, CONFLICTING_ASSOCIATION, CONFLICTING_STATE, CONCURRENT_CHANGE }
	private final Reason reason;
	public OwnedReservationQueryException(Reason reason) {
		super("Owned reservation query failed: " + Objects.requireNonNull(reason));
		this.reason = reason;
	}
	public Reason reason() { return reason; }
}
