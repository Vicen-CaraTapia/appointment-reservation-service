package ar.edu.um.prog2.appointmentreservation.reservation.application.exception;

import java.util.Objects;

/** Deliberately retains neither payload nor broker cause. */
public final class PhonePublicationException extends RuntimeException {
	public enum Reason { INVALID_INTENT, UNKNOWN_OUTCOME }
	private final Reason reason;
	public PhonePublicationException(Reason reason) {
		super("Phone publication outcome: " + Objects.requireNonNull(reason));
		this.reason = reason;
	}
	public Reason reason() { return reason; }
}
