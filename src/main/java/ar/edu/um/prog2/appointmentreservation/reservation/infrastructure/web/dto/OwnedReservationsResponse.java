package ar.edu.um.prog2.appointmentreservation.reservation.infrastructure.web.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import ar.edu.um.prog2.appointmentreservation.reservation.application.model.OwnedReservationPage;
import ar.edu.um.prog2.appointmentreservation.reservation.application.model.TechnicalReservation;
import ar.edu.um.prog2.appointmentreservation.reservation.domain.model.ReservationProcessStatus;

/** Public data for the authenticated user's reservations; excludes external identity and credentials. */
public record OwnedReservationsResponse(List<Reservation> items, long total, int page, int size) {
	public OwnedReservationsResponse {
		items = List.copyOf(items);
	}

	public static OwnedReservationsResponse from(OwnedReservationPage page) {
		return new OwnedReservationsResponse(page.items().stream().map(Reservation::from).toList(),
				page.total(), page.page(), page.size());
	}

	public record Reservation(long id, long professionalId, String professionalFirstName, String professionalLastName,
			LocalDate appointmentDate, LocalTime startTime, LocalTime endTime,
			ReservationProcessStatus processStatus, TechnicalReservation.Status reservationStatus,
			Instant createdAt, Instant confirmedAt, Instant cancelledAt) {
		private static Reservation from(OwnedReservationPage.Item item) {
			var reservation = item.reservation();
			return new Reservation(item.localProcessId(), reservation.professionalId(), reservation.professionalFirstName(),
					reservation.professionalLastName(), reservation.appointmentDate(), reservation.startTime(),
					reservation.endTime(), item.localStatus(), reservation.status(), reservation.createdAt(),
					reservation.confirmedAt(), reservation.cancelledAt());
		}

		@Override
		public String toString() {
			return "OwnedReservation[redacted]";
		}
	}

	@Override
	public String toString() {
		return "OwnedReservationsResponse[redacted]";
	}
}
