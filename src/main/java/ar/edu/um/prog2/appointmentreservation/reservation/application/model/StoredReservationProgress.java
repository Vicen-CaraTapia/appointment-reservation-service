package ar.edu.um.prog2.appointmentreservation.reservation.application.model;

import java.time.Instant;
import ar.edu.um.prog2.appointmentreservation.reservation.domain.model.ReservationProcessStatus;

/** Read-only owned projection; no patient data or external identifiers. */
public record StoredReservationProgress(long id, ReservationProcessStatus status, Instant expiresAt,
		boolean phoneRequestReady, String reason) { }
