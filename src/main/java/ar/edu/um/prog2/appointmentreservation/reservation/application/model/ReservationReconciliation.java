package ar.edu.um.prog2.appointmentreservation.reservation.application.model;

import java.time.Instant;
import ar.edu.um.prog2.appointmentreservation.reservation.domain.model.ReservationProcessStatus;

/** Committed local state after REST verification, separate from the transport observation. */
public record ReservationReconciliation(long localProcessId, ReservationProcessStatus status, Instant verifiedAt) { }
