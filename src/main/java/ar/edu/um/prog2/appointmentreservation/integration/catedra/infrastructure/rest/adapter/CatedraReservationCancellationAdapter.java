package ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.rest.adapter;

import java.util.Objects;
import org.jspecify.annotations.Nullable;
import ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.rest.client.CatedraReservationCancellationClient;
import ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.rest.exception.CatedraHttpException;
import ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.rest.exception.CatedraRestException;
import ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.rest.exception.CatedraTimeoutException;
import ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.rest.exception.CatedraTransportException;
import ar.edu.um.prog2.appointmentreservation.reservation.application.exception.ReservationCancellationException;
import ar.edu.um.prog2.appointmentreservation.reservation.application.exception.ReservationCancellationException.Reason;
import ar.edu.um.prog2.appointmentreservation.reservation.application.model.ReservationCancellation;
import ar.edu.um.prog2.appointmentreservation.reservation.application.port.out.ReservationCanceller;

public final class CatedraReservationCancellationAdapter implements ReservationCanceller {
	private final CatedraReservationCancellationClient client;

	public CatedraReservationCancellationAdapter(CatedraReservationCancellationClient client) {
		this.client = Objects.requireNonNull(client);
	}

	@Override
	public ReservationCancellation cancel(String reservationProcessId, @Nullable String reason) {
		try {
			var response = client.cancel(reservationProcessId, reason);
			return new ReservationCancellation(response.reservationId(), response.reservationProcessId(), response.cancelledAt());
		} catch (CatedraRestException failure) {
			// TIMEOUT, UNAVAILABLE, REMOTE_FAILURE and INVALID_RESPONSE do not prove cancellation failed remotely.
			throw new ReservationCancellationException(reason(failure));
		}
	}

	private static Reason reason(CatedraRestException failure) {
		if (failure instanceof CatedraTimeoutException) { return Reason.TIMEOUT; }
		if (failure instanceof CatedraTransportException) { return Reason.UNAVAILABLE; }
		if (failure instanceof CatedraHttpException http) {
			int status = http.status().value();
			if (status == 404 && "RESERVATION_NOT_FOUND".equals(http.code())) { return Reason.RESERVATION_NOT_FOUND; }
			if (status == 403 && "RESERVATION_OWNER_MISMATCH".equals(http.code())) { return Reason.RESERVATION_OWNER_MISMATCH; }
			if (status == 409 && "RESERVATION_NOT_CANCELLABLE".equals(http.code())) { return Reason.RESERVATION_NOT_CANCELLABLE; }
			if (status == 403 && "STUDENT_INTEGRATION_NOT_FOUND".equals(http.code())) { return Reason.INTEGRATION_NOT_FOUND; }
			if (status >= 500 && status <= 599) { return Reason.REMOTE_FAILURE; }
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
