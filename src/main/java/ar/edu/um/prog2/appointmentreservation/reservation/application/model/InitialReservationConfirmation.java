package ar.edu.um.prog2.appointmentreservation.reservation.application.model;

import java.time.Instant;

/** Patient input combined with an already validated remote initial acceptance. */
public record InitialReservationConfirmation(String reservationProcessId, String patientFirstName,
		String patientLastName, Instant expiresAt) {

	public InitialReservationConfirmation {
		if (reservationProcessId == null || reservationProcessId.isBlank() || expiresAt == null
				|| !validName(patientFirstName) || !validName(patientLastName)) {
			throw new IllegalArgumentException("Initial acceptance requires its process ID, expiration and patient names of 2 to 100 characters");
		}
	}

	private static boolean validName(String value) {
		return value != null && !value.isBlank() && value.length() >= 2 && value.length() <= 100;
	}

	@Override
	public String toString() {
		return "InitialReservationConfirmation[redacted]";
	}
}
