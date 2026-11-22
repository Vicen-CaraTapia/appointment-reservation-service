package ar.edu.um.prog2.appointmentreservation.reservation.application.model;

import java.time.LocalDate;
import java.util.List;

/** Optional technical query filters; externalPatientId is exact and is never normalized. */
public record ReservationListQuery(TechnicalReservation.Status status, LocalDate from, LocalDate to,
		String externalPatientId, Integer page, Integer size, List<String> sort) {
	public ReservationListQuery {
		if ((from != null && to != null && from.isAfter(to)) || (page != null && page < 0) || (size != null && size < 1)) {
			throw new IllegalArgumentException("Valid reservation dates and pagination are required");
		}
		sort = sort == null ? List.of() : List.copyOf(sort);
	}
	@Override public String toString() { return "ReservationListQuery[redacted]"; }
}
