package ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.rest.dto;

import java.util.List;
import java.util.Objects;

/** Infrastructure-only JSON and pagination metadata; never a public user response. */
public record CatedraPaginatedResponse<T>(T body, List<String> totalCountValues, List<String> linkValues) {
	public CatedraPaginatedResponse {
		Objects.requireNonNull(body, "A pagination response body is required");
		totalCountValues = List.copyOf(totalCountValues);
		linkValues = List.copyOf(linkValues);
	}
	@Override
	public String toString() { return "CatedraPaginatedResponse[redacted]"; }
}
