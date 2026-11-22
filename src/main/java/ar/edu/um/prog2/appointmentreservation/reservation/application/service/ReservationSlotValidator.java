package ar.edu.um.prog2.appointmentreservation.reservation.application.service;

import java.util.Objects;
import java.util.Set;

import ar.edu.um.prog2.appointmentreservation.availability.application.service.AvailableAppointmentSlotsCalculator;
import ar.edu.um.prog2.appointmentreservation.professional.application.usecase.LoadEnabledProfessionalUseCase;
import ar.edu.um.prog2.appointmentreservation.reservation.application.exception.InvalidReservationSlotException;
import ar.edu.um.prog2.appointmentreservation.reservation.application.model.StartReservationCommand;

/** Checks current schedule validity only; it does not guarantee that a subsequent hold will succeed. */
public final class ReservationSlotValidator {

	private final LoadEnabledProfessionalUseCase professionals;
	private final AvailableAppointmentSlotsCalculator calculator;

	public ReservationSlotValidator(LoadEnabledProfessionalUseCase professionals,
			AvailableAppointmentSlotsCalculator calculator) {
		this.professionals = Objects.requireNonNull(professionals);
		this.calculator = Objects.requireNonNull(calculator);
	}

	public void validate(StartReservationCommand selection) {
		Objects.requireNonNull(selection, "Appointment selection is required");
		var professional = professionals.load(selection.professionalId());
		// Occupancy and concurrent-hold conflicts remain authoritative at the catedra POST boundary.
		var scheduledSlots = calculator.calculate(selection.date(), professional.weeklySchedules(), Set.of());
		if (!scheduledSlots.contains(selection.startTime())) {
			throw new InvalidReservationSlotException();
		}
	}
}
