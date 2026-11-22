package ar.edu.um.prog2.appointmentreservation.user.application.port.out;

import ar.edu.um.prog2.appointmentreservation.user.application.exception.DuplicateUserException;
import ar.edu.um.prog2.appointmentreservation.user.application.model.NewRegisteredUser;
import ar.edu.um.prog2.appointmentreservation.user.application.model.RegisteredUser;

public interface UserRegistrationStore {

	boolean loginExists(String normalizedLogin);

	boolean emailExists(String normalizedEmail);

	/**
	 * Atomically persists the user and authorities. Uniqueness must also be enforced
	 * here: prechecks alone cannot prevent concurrent duplicate registrations.
	 * @throws DuplicateUserException if a login/email conflict occurs while saving
	 */
	RegisteredUser create(NewRegisteredUser user);
}
