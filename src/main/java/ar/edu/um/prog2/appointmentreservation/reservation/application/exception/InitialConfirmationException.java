package ar.edu.um.prog2.appointmentreservation.reservation.application.exception;

import java.util.Objects;

/** Controlled outcomes; technical failures do not establish that remote acceptance failed. */
public final class InitialConfirmationException extends RuntimeException {

	public enum Reason {
		PROFESSIONAL_NOT_FOUND,
		INVALID_SLOT,
		SLOT_ALREADY_RESERVED,
		HOLD_NOT_FOUND,
		HOLD_EXPIRED,
		HOLD_OWNER_MISMATCH,
		EXTERNAL_PATIENT_MISMATCH,
		RESERVATION_PROCESS_ALREADY_CONFIRMED,
		TIMEOUT,
		UNAVAILABLE,
		UNAUTHORIZED,
		FORBIDDEN,
		REQUEST_REJECTED,
		INVALID_RESPONSE,
		LOCAL_STATE_FAILURE;

		/** Only these documented functional rejections permit releasing this attempt. */
		public boolean definitiveRejection() {
			return switch (this) {
				case PROFESSIONAL_NOT_FOUND, INVALID_SLOT, SLOT_ALREADY_RESERVED, HOLD_NOT_FOUND,
						HOLD_EXPIRED, HOLD_OWNER_MISMATCH, EXTERNAL_PATIENT_MISMATCH -> true;
				default -> false;
			};
		}
	}

	private final Reason reason;

	public InitialConfirmationException(Reason reason) {
		super("Initial hold confirmation failed: " + Objects.requireNonNull(reason));
		this.reason = reason;
	}

	public Reason reason() {
		return reason;
	}
}
