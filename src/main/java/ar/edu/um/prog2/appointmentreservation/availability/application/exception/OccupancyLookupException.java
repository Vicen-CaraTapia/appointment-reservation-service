package ar.edu.um.prog2.appointmentreservation.availability.application.exception;

import java.util.Objects;

/** Controlled application failure; never retains remote payloads, credentials or transport causes. */
public final class OccupancyLookupException extends RuntimeException {

	public enum Reason {
		TIMEOUT,
		UNAVAILABLE,
		UNAUTHORIZED,
		FORBIDDEN,
		NOT_FOUND,
		CONFLICT,
		REQUEST_REJECTED,
		INVALID_RESPONSE
	}

	private final Reason reason;

	public OccupancyLookupException(Reason reason) {
		super("Current occupancy lookup failed: " + Objects.requireNonNull(reason));
		this.reason = reason;
	}

	public Reason reason() {
		return reason;
	}
}
