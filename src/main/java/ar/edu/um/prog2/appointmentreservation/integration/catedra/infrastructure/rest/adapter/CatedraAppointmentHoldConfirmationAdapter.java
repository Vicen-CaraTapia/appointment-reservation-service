package ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.rest.adapter;

import java.util.Objects;

import ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.rest.client.CatedraAppointmentHoldConfirmationClient;
import ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.rest.dto.CatedraAppointmentHoldConfirmationRequest;
import ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.rest.exception.CatedraHoldConfirmationException;
import ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.rest.exception.CatedraHttpException;
import ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.rest.exception.CatedraRestException;
import ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.rest.exception.CatedraTimeoutException;
import ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.rest.exception.CatedraTransportException;
import ar.edu.um.prog2.appointmentreservation.reservation.application.exception.InitialConfirmationException;
import ar.edu.um.prog2.appointmentreservation.reservation.application.exception.InitialConfirmationException.Reason;
import ar.edu.um.prog2.appointmentreservation.reservation.application.model.AppointmentHold;
import ar.edu.um.prog2.appointmentreservation.reservation.application.model.ConfirmationPatient;
import ar.edu.um.prog2.appointmentreservation.reservation.application.model.InitialReservationConfirmation;
import ar.edu.um.prog2.appointmentreservation.reservation.application.port.out.AppointmentHoldConfirmer;

public final class CatedraAppointmentHoldConfirmationAdapter implements AppointmentHoldConfirmer {

	private final CatedraAppointmentHoldConfirmationClient client;

	public CatedraAppointmentHoldConfirmationAdapter(CatedraAppointmentHoldConfirmationClient client) {
		this.client = Objects.requireNonNull(client);
	}

	@Override
	public InitialReservationConfirmation confirm(AppointmentHold hold, String externalPatientId, ConfirmationPatient patient) {
		Objects.requireNonNull(hold, "Owned hold is required");
		Objects.requireNonNull(patient, "Confirmation patient is required");
		var request = new CatedraAppointmentHoldConfirmationRequest(externalPatientId, patient.firstName(), patient.lastName());
		try {
			var response = client.confirm(hold.holdId(), hold.reservationProcessId(), request);
			if (response == null) {
				throw new InitialConfirmationException(Reason.INVALID_RESPONSE);
			}
			return new InitialReservationConfirmation(response.reservationProcessId(), patient.firstName(),
					patient.lastName(), response.expiresAt());
		} catch (CatedraRestException failure) {
			throw new InitialConfirmationException(reason(failure));
		}
	}

	private static Reason reason(CatedraRestException failure) {
		if (failure instanceof CatedraHoldConfirmationException rejected) {
			return switch (rejected.reason()) {
				case PROFESSIONAL_NOT_FOUND -> Reason.PROFESSIONAL_NOT_FOUND;
				case INVALID_SLOT -> Reason.INVALID_SLOT;
				case SLOT_ALREADY_RESERVED -> Reason.SLOT_ALREADY_RESERVED;
				case HOLD_NOT_FOUND -> Reason.HOLD_NOT_FOUND;
				case HOLD_EXPIRED -> Reason.HOLD_EXPIRED;
				case HOLD_OWNER_MISMATCH -> Reason.HOLD_OWNER_MISMATCH;
				case EXTERNAL_PATIENT_MISMATCH -> Reason.EXTERNAL_PATIENT_MISMATCH;
				case RESERVATION_PROCESS_ALREADY_CONFIRMED -> Reason.RESERVATION_PROCESS_ALREADY_CONFIRMED;
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
