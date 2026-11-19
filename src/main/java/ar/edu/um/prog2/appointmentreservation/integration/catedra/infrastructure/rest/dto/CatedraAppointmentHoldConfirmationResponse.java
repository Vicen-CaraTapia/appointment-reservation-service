package ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.rest.dto;

import java.time.Instant;
import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** External initial acceptance; it does not represent final appointment confirmation. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CatedraAppointmentHoldConfirmationResponse(
		@JsonProperty(required = true) String reservationProcessId,
		@JsonProperty(required = true) String status,
		@JsonProperty(required = true) @JsonFormat(shape = JsonFormat.Shape.STRING) Instant expiresAt
) {

	public CatedraAppointmentHoldConfirmationResponse {
		if (reservationProcessId == null || reservationProcessId.isBlank()) {
			throw new IllegalArgumentException("Initial confirmation process ID must be nonblank");
		}
		if (!"WAITING_FOR_PHONE".equals(status)) {
			throw new IllegalArgumentException("Initial confirmation response must have status WAITING_FOR_PHONE");
		}
		Objects.requireNonNull(expiresAt, "Initial confirmation expiration is required");
	}

	@Override
	public String toString() {
		return "CatedraAppointmentHoldConfirmationResponse[redacted]";
	}
}
