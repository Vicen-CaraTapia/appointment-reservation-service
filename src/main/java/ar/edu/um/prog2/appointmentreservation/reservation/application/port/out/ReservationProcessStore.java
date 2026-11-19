package ar.edu.um.prog2.appointmentreservation.reservation.application.port.out;

import java.util.Optional;

import ar.edu.um.prog2.appointmentreservation.reservation.application.model.AppointmentHold;
import ar.edu.um.prog2.appointmentreservation.reservation.application.model.AuthenticatedReservationUser;
import ar.edu.um.prog2.appointmentreservation.reservation.application.model.HeldReservationProcess;
import ar.edu.um.prog2.appointmentreservation.reservation.application.model.StartReservationCommand;
import ar.edu.um.prog2.appointmentreservation.reservation.application.model.StoredReservationProcess;
import ar.edu.um.prog2.appointmentreservation.reservation.application.model.InitialReservationConfirmation;

public interface ReservationProcessStore {

	/**
	 * Atomically saves a local HELD process owned by the authenticated user, preserving
	 * the successful hold's external identifiers and expiration. Patient identity is
	 * derived from this owner, never accepted as an independent ownership parameter.
	 * Local persistence cannot roll back the already-created remote hold.
	 */
	HeldReservationProcess createHeld(AuthenticatedReservationUser owner, StartReservationCommand selection,
			AppointmentHold hold);

	/** Missing and foreign-owned IDs are indistinguishable; no live persistence entity is returned. */
	Optional<StoredReservationProcess> findOwned(long id, AuthenticatedReservationUser owner);

	/**
	 * Records validated initial acceptance only if owner, version, HELD state and external
	 * process ID still match. Commits locally before returning; cannot roll back cátedra.
	 */
	StoredReservationProcess recordInitialConfirmation(long id, long expectedVersion,
			AuthenticatedReservationUser owner, InitialReservationConfirmation confirmation);
}
