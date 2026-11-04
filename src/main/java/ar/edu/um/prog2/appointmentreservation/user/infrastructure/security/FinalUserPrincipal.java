package ar.edu.um.prog2.appointmentreservation.user.infrastructure.security;

import java.util.Collection;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.User;

/** Security principal only; never exposed as a public response or JWT payload. */
public final class FinalUserPrincipal extends User {

	private final long userId;

	public FinalUserPrincipal(long userId, String login, String passwordHash, boolean active,
			Collection<? extends GrantedAuthority> authorities) {
		super(login, passwordHash, active, true, true, true, authorities);
		this.userId = userId;
	}

	public long userId() {
		return userId;
	}

	@Override
	public String toString() {
		return "FinalUserPrincipal[redacted]";
	}
}
