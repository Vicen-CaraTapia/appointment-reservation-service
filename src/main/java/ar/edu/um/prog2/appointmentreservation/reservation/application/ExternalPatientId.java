package ar.edu.um.prog2.appointmentreservation.reservation.application;

/** Derive only from the backend's trusted final-user identity, never a public request owner field. */
public final class ExternalPatientId {

	private ExternalPatientId() {
	}

	public static String fromUserId(long userId) {
		if (userId <= 0) {
			throw new IllegalArgumentException("A positive persisted final-user ID is required");
		}
		return "user-" + userId;
	}
}
