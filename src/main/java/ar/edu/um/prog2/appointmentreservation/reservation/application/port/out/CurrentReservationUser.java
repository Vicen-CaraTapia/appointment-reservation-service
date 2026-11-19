package ar.edu.um.prog2.appointmentreservation.reservation.application.port.out;

import ar.edu.um.prog2.appointmentreservation.reservation.application.exception.ReservationAuthenticationRequiredException;
import ar.edu.um.prog2.appointmentreservation.reservation.application.model.AuthenticatedReservationUser;

public interface CurrentReservationUser {

	/**
	 * Resolves the final-user identity from the trusted authentication context.
	 * @throws ReservationAuthenticationRequiredException when no valid final-user identity is available
	 */
	AuthenticatedReservationUser requireAuthenticated();
}
