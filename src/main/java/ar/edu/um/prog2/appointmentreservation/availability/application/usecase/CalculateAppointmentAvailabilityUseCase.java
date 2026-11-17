package ar.edu.um.prog2.appointmentreservation.availability.application.usecase;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Objects;

import ar.edu.um.prog2.appointmentreservation.availability.application.port.out.CurrentOccupancySource;
import ar.edu.um.prog2.appointmentreservation.availability.application.service.AvailableAppointmentSlotsCalculator;
import ar.edu.um.prog2.appointmentreservation.professional.application.usecase.LoadEnabledProfessionalUseCase;

/** Combines current sources without persisting slots or guaranteeing a later hold. */
public final class CalculateAppointmentAvailabilityUseCase {

	private final LoadEnabledProfessionalUseCase professionals;
	private final CurrentOccupancySource occupancies;
	private final AvailableAppointmentSlotsCalculator calculator;

	public CalculateAppointmentAvailabilityUseCase(LoadEnabledProfessionalUseCase professionals,
			CurrentOccupancySource occupancies, AvailableAppointmentSlotsCalculator calculator) {
		this.professionals = Objects.requireNonNull(professionals);
		this.occupancies = Objects.requireNonNull(occupancies);
		this.calculator = Objects.requireNonNull(calculator);
	}

	public List<LocalTime> calculate(long professionalId, LocalDate date) {
		if (date == null) {
			throw new IllegalArgumentException("Availability date is required");
		}
		var professional = professionals.load(professionalId);
		var schedules = professional.weeklySchedules();
		if (schedules.stream().noneMatch(schedule -> schedule.enabled() && schedule.dayOfWeek().equals(date.getDayOfWeek()))) {
			return List.of();
		}
		var occupied = occupancies.loadBlockedStartTimes(professionalId, date);
		return calculator.calculate(date, schedules, occupied);
	}
}
