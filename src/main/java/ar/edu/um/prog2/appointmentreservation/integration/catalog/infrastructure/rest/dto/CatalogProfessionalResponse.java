package ar.edu.um.prog2.appointmentreservation.integration.catalog.infrastructure.rest.dto;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Transport DTO for the internal Catalog contract; it is not a persistence or domain model. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CatalogProfessionalResponse(
		long id,
		String firstName,
		String lastName,
		boolean enabled,
		Category category,
		List<WeeklySchedule> weeklySchedules
) {

	public CatalogProfessionalResponse {
		weeklySchedules = List.copyOf(weeklySchedules);
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record Category(long id, String name, boolean enabled) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record WeeklySchedule(
			long id,
			DayOfWeek dayOfWeek,
			@JsonFormat(shape = JsonFormat.Shape.STRING) LocalTime startTime,
			@JsonFormat(shape = JsonFormat.Shape.STRING) LocalTime endTime,
			int slotDurationMinutes,
			boolean enabled
	) {
	}
}
