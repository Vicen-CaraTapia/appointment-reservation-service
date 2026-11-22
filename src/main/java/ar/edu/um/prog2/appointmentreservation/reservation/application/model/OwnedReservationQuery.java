package ar.edu.um.prog2.appointmentreservation.reservation.application.model;

import java.time.LocalDate;

/** Publicly usable filters only: owner identity and remote sort are never supplied by the caller. */
public record OwnedReservationQuery(TechnicalReservation.Status status, LocalDate from, LocalDate to, int page, int size) {
	public OwnedReservationQuery {
		if (page < 0 || size < 1 || size > 100 || (from != null && to != null && from.isAfter(to))) {
			throw new IllegalArgumentException("Valid reservation dates, page and size from 1 to 100 are required");
		}
	}
}
