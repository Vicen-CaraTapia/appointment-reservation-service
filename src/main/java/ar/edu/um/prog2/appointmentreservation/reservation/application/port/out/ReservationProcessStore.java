package ar.edu.um.prog2.appointmentreservation.reservation.application.port.out;

import ar.edu.um.prog2.appointmentreservation.reservation.application.model.AppointmentHold;
import ar.edu.um.prog2.appointmentreservation.reservation.application.model.AuthenticatedReservationUser;
import ar.edu.um.prog2.appointmentreservation.reservation.application.model.HeldReservationProcess;
import ar.edu.um.prog2.appointmentreservation.reservation.application.model.StartReservationCommand;

public interface ReservationProcessStore {

	/**
	 * Atomically saves a local HELD process owned by the authenticated user, preserving
	 * the successful hold's external identifiers and expiration. Patient identity is
	 * derived from this owner, never accepted as an independent ownership parameter.
	 * Local persistence cannot roll back the already-created remote hold.
	 */
	HeldReservationProcess createHeld(AuthenticatedReservationUser owner, StartReservationCommand selection,
			AppointmentHold hold);
}
