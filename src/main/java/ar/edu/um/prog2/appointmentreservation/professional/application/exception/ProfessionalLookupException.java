package ar.edu.um.prog2.appointmentreservation.professional.application.exception;

import java.util.Objects;

/** Application outcomes independent of HTTP, credentials and transport exception messages. */
public final class ProfessionalLookupException extends RuntimeException {

	public enum Reason {
		CATALOG_TIMEOUT,
		CATALOG_UNAVAILABLE,
		CATALOG_UNAUTHORIZED,
		CATALOG_FORBIDDEN,
		INVALID_CATALOG_RESPONSE,
		NOT_FOUND,
		DISABLED
	}

	private final Reason reason;

	public ProfessionalLookupException(Reason reason) {
		super("Current professional lookup failed: " + Objects.requireNonNull(reason));
		this.reason = reason;
	}

	public Reason reason() {
		return reason;
	}
}
