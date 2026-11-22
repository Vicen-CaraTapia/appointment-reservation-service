package ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.rest.dto;

import org.jspecify.annotations.Nullable;

/** Only the stable functional code is retained; remote detail and other fields are ignored. */
public record CatedraProblemDetails(@Nullable String code) {

	public static CatedraProblemDetails empty() {
		return new CatedraProblemDetails(null);
	}

	@Override
	public String toString() {
		return "CatedraProblemDetails[redacted]";
	}
}
