package ar.edu.um.prog2.appointmentreservation.reservation.application.port.out;

import ar.edu.um.prog2.appointmentreservation.reservation.application.model.AppointmentHold;
import ar.edu.um.prog2.appointmentreservation.reservation.application.model.StartReservationCommand;

public interface AppointmentHoldRequester {

	/**
	 * Requests one hold using the application-derived patient identity.
	 * A timeout or invalid response does not establish that the remote hold was rejected;
	 * callers must not blindly repeat the operation.
	 */
	AppointmentHold createHold(StartReservationCommand selection, String externalPatientId);
}
