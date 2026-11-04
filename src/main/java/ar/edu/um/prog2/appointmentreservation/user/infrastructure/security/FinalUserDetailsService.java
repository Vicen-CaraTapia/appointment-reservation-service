package ar.edu.um.prog2.appointmentreservation.user.infrastructure.security;

import java.util.Locale;

import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import ar.edu.um.prog2.appointmentreservation.user.infrastructure.persistence.repository.UserJpaRepository;

public final class FinalUserDetailsService implements UserDetailsService {

	private final UserJpaRepository users;

	public FinalUserDetailsService(UserJpaRepository users) {
		this.users = users;
	}

	@Override
	public FinalUserPrincipal loadUserByUsername(String login) {
		if (login == null || login.isBlank()) {
			throw new UsernameNotFoundException("Invalid credentials");
		}
		var user = users.findByLoginIgnoreCase(login.toLowerCase(Locale.ROOT))
				.orElseThrow(() -> new UsernameNotFoundException("Invalid credentials"));
		return new FinalUserPrincipal(user.getId(), user.getLogin(), user.getPasswordHash(), user.isActivated(),
				user.getAuthorities().stream().map(role -> new SimpleGrantedAuthority(role.getName())).toList());
	}
}
