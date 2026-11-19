package ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.rest.adapter;

import java.util.Objects;

import ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.rest.client.CatedraAppointmentHoldClient;
import ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.rest.dto.CatedraAppointmentHoldRequest;
import ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.rest.exception.CatedraHoldException;
import ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.rest.exception.CatedraHttpException;
import ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.rest.exception.CatedraRestException;
import ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.rest.exception.CatedraTimeoutException;
import ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.rest.exception.CatedraTransportException;
import ar.edu.um.prog2.appointmentreservation.reservation.application.exception.HoldRequestException;
import ar.edu.um.prog2.appointmentreservation.reservation.application.exception.HoldRequestException.Reason;
import ar.edu.um.prog2.appointmentreservation.reservation.application.model.AppointmentHold;
import ar.edu.um.prog2.appointmentreservation.reservation.application.model.StartReservationCommand;
import ar.edu.um.prog2.appointmentreservation.reservation.application.port.out.AppointmentHoldRequester;

public final class CatedraAppointmentHoldAdapter implements AppointmentHoldRequester {

	private final CatedraAppointmentHoldClient client;

	public CatedraAppointmentHoldAdapter(CatedraAppointmentHoldClient client) {
		this.client = Objects.requireNonNull(client);
	}

	@Override
	public AppointmentHold createHold(StartReservationCommand selection, String externalPatientId) {
		Objects.requireNonNull(selection, "Appointment selection is required");
		var request = new CatedraAppointmentHoldRequest(selection.professionalId(), selection.date(),
				selection.startTime(), externalPatientId);
		try {
			var response = client.createHold(request);
			return new AppointmentHold(response.holdId(), response.reservationProcessId(), response.expiresAt());
		} catch (CatedraRestException failure) {
			// Discard technical causes and untrusted remote text; preserve only controlled outcomes.
			throw new HoldRequestException(reason(failure));
		}
	}

	private static Reason reason(CatedraRestException failure) {
		if (failure instanceof CatedraHoldException rejected) {
			return switch (rejected.reason()) {
				case PROFESSIONAL_NOT_FOUND -> Reason.PROFESSIONAL_NOT_FOUND;
				case PROFESSIONAL_DISABLED -> Reason.PROFESSIONAL_DISABLED;
				case INVALID_SLOT -> Reason.INVALID_SLOT;
				case SLOT_ALREADY_RESERVED -> Reason.SLOT_ALREADY_RESERVED;
				case SLOT_ALREADY_HELD -> Reason.SLOT_ALREADY_HELD;
			};
		}
		if (failure instanceof CatedraTimeoutException) {
			return Reason.TIMEOUT;
		}
		if (failure instanceof CatedraTransportException) {
			return Reason.UNAVAILABLE;
		}
		if (failure instanceof CatedraHttpException http) {
			int status = http.status().value();
			if (status >= 500 && status <= 599) {
				return Reason.UNAVAILABLE;
			}
			return switch (status) {
				case 401 -> Reason.UNAUTHORIZED;
				case 403 -> Reason.FORBIDDEN;
				default -> status >= 400 && status <= 499 ? Reason.REQUEST_REJECTED : Reason.INVALID_RESPONSE;
			};
		}
		return Reason.INVALID_RESPONSE;
	}
}
