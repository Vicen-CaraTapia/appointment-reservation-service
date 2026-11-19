package ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.rest.dto;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;

/** Outbound cátedra contract; the application supplies the trusted external patient identity. */
public record CatedraAppointmentHoldRequest(
		@JsonProperty(required = true) @JsonSetter(nulls = Nulls.FAIL) long professionalId,
		@JsonProperty(required = true) @JsonFormat(shape = JsonFormat.Shape.STRING) LocalDate date,
		@JsonProperty(required = true) @JsonFormat(shape = JsonFormat.Shape.STRING) LocalTime startTime,
		@JsonProperty(required = true) String externalPatientId
) {

	public CatedraAppointmentHoldRequest {
		Objects.requireNonNull(date, "Hold appointment date is required");
		Objects.requireNonNull(startTime, "Hold appointment start time is required");
		if (externalPatientId == null || externalPatientId.isBlank() || externalPatientId.length() > 100) {
			throw new IllegalArgumentException("External patient ID must be nonblank and at most 100 characters");
		}
	}

	@Override
	public String toString() {
		return "CatedraAppointmentHoldRequest[redacted]";
	}
}
