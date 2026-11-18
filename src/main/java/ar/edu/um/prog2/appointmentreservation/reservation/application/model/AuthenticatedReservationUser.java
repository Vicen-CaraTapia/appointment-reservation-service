package ar.edu.um.prog2.appointmentreservation.reservation.application.model;

/** Identity supplied by the security adapter, never by a public request's owner fields. */
public record AuthenticatedReservationUser(long userId, String login) {

	public AuthenticatedReservationUser {
		if (userId <= 0 || login == null || login.isBlank() || login.length() > 50) {
			throw new IllegalArgumentException("A persisted final-user identity and audit login are required");
		}
	}

	@Override
	public String toString() {
		return "AuthenticatedReservationUser[redacted]";
	}
}
