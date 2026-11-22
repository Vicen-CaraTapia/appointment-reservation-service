package ar.edu.um.prog2.appointmentreservation.reservation.application.model;

import java.time.Instant;

/** Validated external cancellation result; does not by itself update local workflow state. */
public record ReservationCancellation(long reservationId, String reservationProcessId, Instant cancelledAt) {
	@Override
	public String toString() { return "ReservationCancellation[redacted]"; }
}
