package ar.edu.um.prog2.appointmentreservation.reservation.application.exception;

import java.util.Objects;

public final class PhoneSubmissionStateException extends RuntimeException {
	public enum Reason { NOT_FOUND, CONFLICT, EXPIRED, STORAGE_FAILURE }
	private final Reason reason;
	public PhoneSubmissionStateException(Reason reason) {
		super("Phone submission storage outcome: " + Objects.requireNonNull(reason));
		this.reason = reason;
	}
	public Reason reason() { return reason; }
}
