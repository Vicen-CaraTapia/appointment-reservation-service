package ar.edu.um.prog2.appointmentreservation.reservation.application.model;

import java.time.LocalDate;
import java.time.LocalTime;

/** Immutable local identity evidence, not an external assertion of ownership. */
public record ReservationAssociation(long localProcessId, long ownerUserId, String externalPatientId,
		String externalProcessId, Long externalReservationId, long professionalId, LocalDate appointmentDate, LocalTime startTime) {
	@Override
	public String toString() { return "ReservationAssociation[redacted]"; }
}
