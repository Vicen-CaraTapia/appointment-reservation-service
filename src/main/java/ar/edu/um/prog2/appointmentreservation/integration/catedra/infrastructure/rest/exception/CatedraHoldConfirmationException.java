package ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.rest.exception;

import java.util.Objects;

/** Only the official status/code pairs establish a functional initial-confirmation rejection. */
public final class CatedraHoldConfirmationException extends CatedraRestException {

	public enum Reason {
		PROFESSIONAL_NOT_FOUND(404),
		INVALID_SLOT(400),
		SLOT_ALREADY_RESERVED(409),
		HOLD_NOT_FOUND(404),
		HOLD_EXPIRED(404),
		HOLD_OWNER_MISMATCH(403),
		EXTERNAL_PATIENT_MISMATCH(400),
		RESERVATION_PROCESS_ALREADY_CONFIRMED(409);

		private final int httpStatus;

		Reason(int httpStatus) {
			this.httpStatus = httpStatus;
		}

		public int httpStatus() {
			return httpStatus;
		}
	}

	private final Reason reason;

	public CatedraHoldConfirmationException(Reason reason) {
		super("Catedra initial confirmation rejected: " + Objects.requireNonNull(reason));
		this.reason = reason;
	}

	public Reason reason() {
		return reason;
	}
}
