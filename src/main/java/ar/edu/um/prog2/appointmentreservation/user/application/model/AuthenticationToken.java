package ar.edu.um.prog2.appointmentreservation.user.application.model;

public record AuthenticationToken(String value) {
	@Override
	public String toString() { return "AuthenticationToken[redacted]"; }
}
