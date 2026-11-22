package ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.rest.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/** Validated external listing. Link values are retained as metadata, never followed or forwarded to KMP. */
public record CatedraReservationPage(List<Reservation> reservations, long totalCount, List<String> links) {
	public CatedraReservationPage { reservations = List.copyOf(reservations); links = List.copyOf(links); }
	@Override public String toString() { return "CatedraReservationPage[redacted]"; }
	public record Reservation(long reservationId, String reservationProcessId, String externalPatientId,
			LocalDate appointmentDate, LocalTime startTime, LocalTime endTime, long professionalId,
			String professionalFirstName, String professionalLastName, String patientFirstName, String patientLastName,
			String patientPhone, String status, Instant createdAt, Instant confirmedAt, Instant cancelledAt,
			String cancellationReason) {
		@Override public String toString() { return "CatedraReservation[redacted]"; }
	}
}
