package ar.edu.um.prog2.appointmentreservation.reservation.application.model;

/** Validated patient input retained with the durable initial-confirmation attempt. */
public record ConfirmationPatient(String firstName, String lastName) {

	public ConfirmationPatient {
		if (!validName(firstName) || !validName(lastName)) {
			throw new IllegalArgumentException("Patient names must be nonblank and contain 2 to 100 characters");
		}
	}

	private static boolean validName(String value) {
		return value != null && !value.isBlank() && value.length() >= 2 && value.length() <= 100;
	}

	@Override
	public String toString() {
		return "ConfirmationPatient[redacted]";
	}
}
