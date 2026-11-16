package ar.edu.um.prog2.appointmentreservation.availability.application.service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

import ar.edu.um.prog2.appointmentreservation.professional.application.model.CurrentProfessional.WeeklySchedule;

/** Pure calculation for one date; callers supply current schedules and occupied start times. */
public final class AvailableAppointmentSlotsCalculator {

	public List<LocalTime> calculate(LocalDate date, List<WeeklySchedule> schedules, Set<LocalTime> occupiedStartTimes) {
		Objects.requireNonNull(date, "Availability date is required");
		Objects.requireNonNull(schedules, "Current weekly schedules are required");
		Objects.requireNonNull(occupiedStartTimes, "Current occupied start times are required");
		var available = new TreeSet<LocalTime>();
		for (var schedule : schedules) {
			if (!schedule.enabled() || schedule.dayOfWeek() != date.getDayOfWeek()) {
				continue;
			}
			if (schedule.slotDurationMinutes() <= 0 || !schedule.startTime().isBefore(schedule.endTime())) {
				throw new IllegalArgumentException(
						"Applicable weekly schedule must have a positive slot duration and start before end");
			}
			var duration = Duration.ofMinutes(schedule.slotDurationMinutes());
			var start = schedule.startTime();
			// Check the remaining interval before advancing LocalTime, avoiding midnight wraparound.
			while (Duration.between(start, schedule.endTime()).compareTo(duration) >= 0) {
				if (!occupiedStartTimes.contains(start)) {
					available.add(start);
				}
				start = start.plus(duration);
			}
		}
		return List.copyOf(available);
	}
}
