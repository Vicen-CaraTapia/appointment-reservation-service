package ar.edu.um.prog2.appointmentreservation.reservation.application.model;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;

/** Technical-account observation, not proof of final-user ownership or a current Catalog replica. */
public record TechnicalReservation(long reservationId, String reservationProcessId, String externalPatientId,
		LocalDate appointmentDate, LocalTime startTime, LocalTime endTime, long professionalId,
		String professionalFirstName, String professionalLastName, String patientFirstName, String patientLastName,
		String patientPhone, Status status, Instant createdAt, Instant confirmedAt, Instant cancelledAt,
		String cancellationReason) {
	public enum Status { PHONE_PENDING, CONFIRMED, CANCELLED, FAILED }
	@Override public String toString() { return "TechnicalReservation[redacted]"; }
}
