package ar.edu.um.prog2.appointmentreservation.user.application.model;

/** Validated public input, mapped at the HTTP boundary. */
public record RegisterUserCommand(String login, String password, String firstName, String lastName,
		String email, String imageUrl, String langKey) {

	@Override
	public String toString() {
		return "RegisterUserCommand[redacted]";
	}
}
