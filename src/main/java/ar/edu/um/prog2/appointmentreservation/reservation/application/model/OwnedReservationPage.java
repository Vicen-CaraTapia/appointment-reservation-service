package ar.edu.um.prog2.appointmentreservation.reservation.application.model;

import java.util.List;
import java.time.Instant;
import ar.edu.um.prog2.appointmentreservation.reservation.domain.model.ReservationProcessStatus;

/** Only correlated, authorized observations; totals and pagination never use unassociated technical rows. */
public record OwnedReservationPage(List<Item> items, long total, int page, int size) {
	public OwnedReservationPage { items = List.copyOf(items); }
	public record Item(long localProcessId, TechnicalReservation reservation, ReservationProcessStatus localStatus, Instant verifiedAt) {
		/** Internal association candidate. Only reconciled items are returned by the listing use case. */
		public Item(long localProcessId, TechnicalReservation reservation) { this(localProcessId, reservation, null, null); }
		@Override
		public String toString() { return "OwnedReservation[redacted]"; }
	}
	@Override
	public String toString() { return "OwnedReservationPage[redacted]"; }
}
