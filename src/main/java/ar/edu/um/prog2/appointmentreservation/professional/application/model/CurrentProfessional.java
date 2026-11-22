package ar.edu.um.prog2.appointmentreservation.professional.application.model;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.List;
import java.util.Objects;

/** Immutable lookup result, used in memory; Catalog remains the owner of current data. */
public record CurrentProfessional(
		long id,
		String firstName,
		String lastName,
		boolean enabled,
		Category category,
		List<WeeklySchedule> weeklySchedules
) {

	public CurrentProfessional {
		Objects.requireNonNull(firstName);
		Objects.requireNonNull(lastName);
		Objects.requireNonNull(category);
		weeklySchedules = List.copyOf(weeklySchedules);
	}

	public record Category(long id, String name, boolean enabled) {
		public Category {
			Objects.requireNonNull(name);
		}
	}

	public record WeeklySchedule(long id, DayOfWeek dayOfWeek, LocalTime startTime, LocalTime endTime,
			int slotDurationMinutes, boolean enabled) {
		public WeeklySchedule {
			Objects.requireNonNull(dayOfWeek);
			Objects.requireNonNull(startTime);
			Objects.requireNonNull(endTime);
		}
	}
}
