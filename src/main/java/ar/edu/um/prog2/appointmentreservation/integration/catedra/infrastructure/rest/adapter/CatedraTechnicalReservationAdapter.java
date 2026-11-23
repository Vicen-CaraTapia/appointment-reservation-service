package ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.rest.adapter;

import ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.rest.client.CatedraReservationListingClient;
import ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.rest.exception.CatedraRestException;
import ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.rest.exception.CatedraHttpException;
import ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.rest.exception.CatedraTimeoutException;
import ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.rest.exception.CatedraTransportException;
import ar.edu.um.prog2.appointmentreservation.reservation.application.exception.ReservationListingException;
import ar.edu.um.prog2.appointmentreservation.reservation.application.exception.ReservationListingException.Reason;
import ar.edu.um.prog2.appointmentreservation.reservation.application.model.ReservationListQuery;
import ar.edu.um.prog2.appointmentreservation.reservation.application.model.TechnicalReservation;
import ar.edu.um.prog2.appointmentreservation.reservation.application.model.TechnicalReservationPage;
import ar.edu.um.prog2.appointmentreservation.reservation.application.port.out.TechnicalReservationSource;

public final class CatedraTechnicalReservationAdapter implements TechnicalReservationSource {
	private final CatedraReservationListingClient client;

	public CatedraTechnicalReservationAdapter(CatedraReservationListingClient client) {
		this.client = client;
	}

	@Override
	public TechnicalReservationPage list(ReservationListQuery query) {
		try {
			var page = client.list(query);
			var reservations = page.reservations().stream().map(row -> new TechnicalReservation(
					row.reservationId(), row.reservationProcessId(), row.externalPatientId(), row.appointmentDate(),
					row.startTime(), row.endTime(), row.professionalId(), row.professionalFirstName(),
					row.professionalLastName(), row.patientFirstName(), row.patientLastName(), row.patientPhone(),
					TechnicalReservation.Status.valueOf(row.status()), row.createdAt(), row.confirmedAt(),
					row.cancelledAt(), row.cancellationReason())).toList();
			return new TechnicalReservationPage(reservations, page.totalCount());
		} catch (CatedraRestException failure) {
			throw new ReservationListingException(reason(failure));
		}
	}

	private static Reason reason(CatedraRestException failure) {
		if (failure instanceof CatedraTimeoutException) { return Reason.TIMEOUT; }
		if (failure instanceof CatedraTransportException) { return Reason.UNAVAILABLE; }
		if (failure instanceof CatedraHttpException http) {
			int status = http.status().value();
			if (status == 400 && "INVALID_DATE_RANGE".equals(http.code())) { return Reason.INVALID_DATE_RANGE; }
			if (status == 403 && "STUDENT_INTEGRATION_NOT_FOUND".equals(http.code())) { return Reason.INTEGRATION_NOT_FOUND; }
			if (status >= 500 && status <= 599) { return Reason.UNAVAILABLE; }
			return switch (status) {
				case 401 -> Reason.UNAUTHORIZED;
				case 403 -> Reason.FORBIDDEN;
				case 404 -> Reason.NOT_FOUND;
				case 409 -> Reason.CONFLICT;
				default -> status >= 400 && status <= 499 ? Reason.REQUEST_REJECTED : Reason.INVALID_RESPONSE;
			};
		}
		return Reason.INVALID_RESPONSE;
	}
}
