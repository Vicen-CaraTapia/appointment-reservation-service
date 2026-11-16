package ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.rest.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.jspecify.annotations.Nullable;

/** Transport response from the cátedra; it is not a persisted source of availability. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CatedraAppointmentOccupanciesResponse(
		@JsonProperty(required = true) long professionalId,
		@JsonFormat(shape = JsonFormat.Shape.STRING) LocalDate from,
		@JsonFormat(shape = JsonFormat.Shape.STRING) LocalDate to,
		List<Occupancy> occupancies
) {

	public CatedraAppointmentOccupanciesResponse {
		Objects.requireNonNull(from, "Occupancy range start is required");
		Objects.requireNonNull(to, "Occupancy range end is required");
		occupancies = List.copyOf(occupancies);
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record Occupancy(
			@JsonFormat(shape = JsonFormat.Shape.STRING) LocalDate date,
			@JsonFormat(shape = JsonFormat.Shape.STRING) LocalTime startTime,
			Status status,
			@JsonFormat(shape = JsonFormat.Shape.STRING) @Nullable Instant expiresAt
	) {
		public Occupancy {
			Objects.requireNonNull(date, "Occupancy date is required");
			Objects.requireNonNull(startTime, "Occupancy start time is required");
			Objects.requireNonNull(status, "Occupancy status is required");
		}
	}

	public enum Status {
		CONFIRMED,
		HELD
	}
}
