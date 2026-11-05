package ar.edu.um.prog2.appointmentreservation.user.infrastructure.web.dto;

/** Public profile only: excludes credentials, administrative state and audit. */
public record RegisterUserResponse(String login, String firstName, String lastName,
		String email, String imageUrl, String langKey) {
}
