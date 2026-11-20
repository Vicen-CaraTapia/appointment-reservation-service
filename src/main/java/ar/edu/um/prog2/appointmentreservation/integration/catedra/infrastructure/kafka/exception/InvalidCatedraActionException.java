package ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.kafka.exception;

import java.util.Objects;

/** Technical classification only; never retains the external payload or parser cause. */
public final class InvalidCatedraActionException extends RuntimeException {
	public enum Reason { INVALID_PAYLOAD, UNSUPPORTED_VERSION, UNSUPPORTED_EVENT, KEY_MISMATCH, GROUP_MISMATCH }
	private final Reason reason;

	public InvalidCatedraActionException(Reason reason) {
		super("Invalid catedra appointment action: " + Objects.requireNonNull(reason));
		this.reason = reason;
	}

	public Reason reason() { return reason; }
}
