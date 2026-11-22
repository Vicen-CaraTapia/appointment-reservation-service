package ar.edu.um.prog2.appointmentreservation.availability.infrastructure.web.dto;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonFormat;

/** Public availability only; no infrastructure credentials or user security details. */
public record AppointmentAvailabilityResponse(
		long professionalId,
		@JsonFormat(shape = JsonFormat.Shape.STRING) LocalDate date,
		List<String> slots
) {

	public AppointmentAvailabilityResponse {
		Objects.requireNonNull(date);
		slots = List.copyOf(slots);
	}
}
