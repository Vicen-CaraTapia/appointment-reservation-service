package ar.edu.um.prog2.appointmentreservation.user.application.model;

import java.util.Set;

/** Registration result deliberately excludes credentials and audit data. */
public record RegisteredUser(Long id, String login, String firstName, String lastName, String email,
		String imageUrl, String langKey, boolean activated, Set<String> authorities) {

	public RegisteredUser {
		authorities = Set.copyOf(authorities);
	}
}
