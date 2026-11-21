package ar.edu.um.prog2.appointmentreservation.reservation.application.exception;

/** Contains no user input; safe to translate at the public boundary. */
public final class InvalidPhoneNumberException extends RuntimeException {
	public InvalidPhoneNumberException() {
		super("A phone number with 7 to 15 digits and an optional leading plus is required");
	}
}
