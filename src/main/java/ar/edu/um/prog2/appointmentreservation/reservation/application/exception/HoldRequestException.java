package ar.edu.um.prog2.appointmentreservation.reservation.application.exception;

import java.util.Objects;

/** Application outcomes; technical failures do not establish that no remote hold exists. */
public final class HoldRequestException extends RuntimeException {

	public enum Reason {
		PROFESSIONAL_NOT_FOUND,
		PROFESSIONAL_DISABLED,
		INVALID_SLOT,
		SLOT_ALREADY_RESERVED,
		SLOT_ALREADY_HELD,
		TIMEOUT,
		UNAVAILABLE,
		UNAUTHORIZED,
		FORBIDDEN,
		REQUEST_REJECTED,
		INVALID_RESPONSE
	}

	private final Reason reason;

	public HoldRequestException(Reason reason) {
		super("Appointment hold request failed: " + Objects.requireNonNull(reason));
		this.reason = reason;
	}

	public Reason reason() {
		return reason;
	}
}
