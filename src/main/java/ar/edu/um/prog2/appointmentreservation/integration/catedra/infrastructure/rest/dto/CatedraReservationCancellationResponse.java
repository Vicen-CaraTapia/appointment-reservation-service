package ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.rest.dto;

import java.time.Instant;

public record CatedraReservationCancellationResponse(long reservationId, String reservationProcessId,
		String status, Instant cancelledAt) {
	@Override
	public String toString() { return "CatedraReservationCancellationResponse[redacted]"; }
}
