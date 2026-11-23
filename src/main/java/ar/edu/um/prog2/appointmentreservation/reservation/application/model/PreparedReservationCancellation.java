package ar.edu.um.prog2.appointmentreservation.reservation.application.model;

import java.time.Instant;
import ar.edu.um.prog2.appointmentreservation.reservation.domain.model.ReservationProcessStatus;

/** Committed owned observation. Only a new claim authorizes the first remote POST. */
public record PreparedReservationCancellation(long localProcessId, long reservationId,
        String externalProcessId, ReservationProcessStatus status, String reason,
        Instant requestedAt, boolean newlyClaimed) {
    @Override
    public String toString() { return "PreparedReservationCancellation[redacted]"; }
}
