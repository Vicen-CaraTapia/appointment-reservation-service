package ar.edu.um.prog2.appointmentreservation.reservation.application.model;

import java.util.List;

/** Only correlated, authorized observations; totals and pagination never use unassociated technical rows. */
public record OwnedReservationPage(List<Item> items, long total, int page, int size) {
	public OwnedReservationPage { items = List.copyOf(items); }
	public record Item(long localProcessId, TechnicalReservation reservation) {
		@Override
		public String toString() { return "OwnedReservation[redacted]"; }
	}
	@Override
	public String toString() { return "OwnedReservationPage[redacted]"; }
}
