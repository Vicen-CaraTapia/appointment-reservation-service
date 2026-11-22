package ar.edu.um.prog2.appointmentreservation.reservation.application.model;

import java.util.List;

/** Count belongs to the technical query, not an authorized public user listing. */
public record TechnicalReservationPage(List<TechnicalReservation> reservations, long totalCount) {
	public TechnicalReservationPage { reservations = List.copyOf(reservations); }
	@Override public String toString() { return "TechnicalReservationPage[redacted]"; }
}
