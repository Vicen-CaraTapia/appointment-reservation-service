package ar.edu.um.prog2.appointmentreservation.reservation.application.model;

import java.time.LocalDate;
import java.time.LocalTime;

/** User selection only. Ownership and patient identity must come from trusted application logic. */
public record StartReservationCommand(long professionalId, LocalDate date, LocalTime startTime) {

	public StartReservationCommand {
		if (date == null || startTime == null) {
			throw new IllegalArgumentException("Appointment date and start time are required");
		}
	}
}
