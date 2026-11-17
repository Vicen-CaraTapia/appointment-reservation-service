package ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.rest.exception;

import java.util.Objects;

/** Only the documented status/code pairs establish a definitive functional rejection. */
public final class CatedraHoldException extends CatedraRestException {

	public enum Reason {
		PROFESSIONAL_NOT_FOUND(404),
		PROFESSIONAL_DISABLED(400),
		INVALID_SLOT(400),
		SLOT_ALREADY_RESERVED(409),
		SLOT_ALREADY_HELD(409);

		private final int httpStatus;

		Reason(int httpStatus) {
			this.httpStatus = httpStatus;
		}

		public int httpStatus() {
			return httpStatus;
		}
	}

	private final Reason reason;

	public CatedraHoldException(Reason reason) {
		super("Catedra hold request rejected: " + Objects.requireNonNull(reason));
		this.reason = reason;
	}

	public Reason reason() {
		return reason;
	}
}
