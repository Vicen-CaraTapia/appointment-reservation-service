package ar.edu.um.prog2.appointmentreservation.user.application.exception;

public final class AuthenticationUnavailableException extends RuntimeException {
	public AuthenticationUnavailableException() { super("Authentication temporarily unavailable"); }
}
