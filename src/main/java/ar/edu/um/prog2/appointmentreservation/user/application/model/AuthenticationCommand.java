package ar.edu.um.prog2.appointmentreservation.user.application.model;

/** Validated login input; no email lookup or password normalization. */
public record AuthenticationCommand(String username, String password, boolean rememberMe) {
	@Override
	public String toString() { return "AuthenticationCommand[redacted]"; }
}
