package ar.edu.um.prog2.appointmentreservation.user.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.anyString;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;

import ar.edu.um.prog2.appointmentreservation.user.infrastructure.config.UserRegistrationConfiguration;

class FinalUserAuthenticationConfigurationTest {

	private static final String PASSWORD = "  Test-only passphrase  ";
	private final FinalUserDetailsService users = mock(FinalUserDetailsService.class);
	private final PasswordEncoder encoder = spy(new FinalUserSecurityConfiguration().passwordEncoder());
	private final AuthenticationManager manager = new FinalUserSecurityConfiguration()
			.finalUserAuthenticationManager(users, encoder);

	@Test
	void authenticatesWithTheExistingArgon2idEncoderAndErasesCredentials() {
		var loaded = principal(true);
		when(users.loadUserByUsername("alice")).thenReturn(loaded);
		var authentication = manager.authenticate(UsernamePasswordAuthenticationToken.unauthenticated("alice", PASSWORD));
		assertThat(authentication.isAuthenticated()).isTrue();
		assertThat(authentication.getName()).isEqualTo("alice");
		assertThat(authentication.getCredentials()).isNull();
		FinalUserPrincipal user = (FinalUserPrincipal) authentication.getPrincipal();
		assertThat(user.userId()).isEqualTo(42);
		assertThat(user.getPassword()).isNull();
		assertThat(user.getAuthorities()).extracting("authority").containsExactly("ROLE_USER");
		verify(encoder).matches(org.mockito.ArgumentMatchers.eq(PASSWORD), anyString());
	}

	@Test
	void rejectsIncorrectPassword() {
		var loaded = principal(true);
		when(users.loadUserByUsername("alice")).thenReturn(loaded);
		assertThatThrownBy(() -> manager.authenticate(UsernamePasswordAuthenticationToken.unauthenticated(
				"alice", "incorrect-test-passphrase"))).isInstanceOf(BadCredentialsException.class);
	}

	@Test
	void doesNotTrimPasswordBeforeVerification() {
		var loaded = principal(true);
		when(users.loadUserByUsername("alice")).thenReturn(loaded);
		assertThatThrownBy(() -> manager.authenticate(UsernamePasswordAuthenticationToken.unauthenticated(
				"alice", PASSWORD.strip()))).isInstanceOf(BadCredentialsException.class);
	}

	@Test
	void hidesUnknownUserDetails() {
		when(users.loadUserByUsername("missing")).thenThrow(new UsernameNotFoundException("private diagnostic"));
		assertThatThrownBy(() -> manager.authenticate(UsernamePasswordAuthenticationToken.unauthenticated(
				"missing", PASSWORD))).isInstanceOf(BadCredentialsException.class)
				.hasMessageNotContaining("private diagnostic");
	}

	@Test
	void rejectsInactiveUser() {
		var loaded = principal(false);
		when(users.loadUserByUsername("alice")).thenReturn(loaded);
		assertThatThrownBy(() -> manager.authenticate(UsernamePasswordAuthenticationToken.unauthenticated(
				"alice", PASSWORD))).isInstanceOf(DisabledException.class);
	}

	private FinalUserPrincipal principal(boolean active) {
		String hash = new UserRegistrationConfiguration().passwordHasher(encoder).hash(PASSWORD);
		return new FinalUserPrincipal(42L, "alice", hash, active,
				List.of(new SimpleGrantedAuthority("ROLE_USER")));
	}
}
