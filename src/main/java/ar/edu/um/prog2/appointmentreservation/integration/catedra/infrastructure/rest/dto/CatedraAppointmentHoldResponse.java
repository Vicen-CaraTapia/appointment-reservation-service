package ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.rest.dto;

import java.time.Instant;
import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Successful external hold response; separate from Appointment's local process states. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CatedraAppointmentHoldResponse(
		@JsonProperty(required = true) String holdId,
		@JsonProperty(required = true) String reservationProcessId,
		@JsonProperty(required = true) String status,
		@JsonProperty(required = true) @JsonFormat(shape = JsonFormat.Shape.STRING) Instant expiresAt
) {

	public CatedraAppointmentHoldResponse {
		if (holdId == null || holdId.isBlank() || reservationProcessId == null || reservationProcessId.isBlank()) {
			throw new IllegalArgumentException("Held identifiers must be nonblank");
		}
		if (!"HELD".equals(status)) {
			throw new IllegalArgumentException("Successful hold response must have status HELD");
		}
		Objects.requireNonNull(expiresAt, "Hold expiration is required");
	}

	@Override
	public String toString() {
		return "CatedraAppointmentHoldResponse[redacted]";
	}
}
