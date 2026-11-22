package ar.edu.um.prog2.appointmentreservation.user.application.model;

import java.util.Set;

/** Persistence input containing an encoded password, never the plaintext. */
public record NewRegisteredUser(String login, String passwordHash, String firstName, String lastName,
		String email, String imageUrl, String langKey, boolean activated, Set<String> authorities,
		String createdBy) {

	public NewRegisteredUser {
		authorities = Set.copyOf(authorities);
	}

	@Override
	public String toString() {
		return "NewRegisteredUser[redacted]";
	}
}
