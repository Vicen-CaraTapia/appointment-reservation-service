package ar.edu.um.prog2.appointmentreservation.user.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import ar.edu.um.prog2.appointmentreservation.user.infrastructure.persistence.entity.AuthorityEntity;
import ar.edu.um.prog2.appointmentreservation.user.infrastructure.persistence.entity.UserEntity;
import ar.edu.um.prog2.appointmentreservation.user.infrastructure.persistence.repository.UserJpaRepository;

class FinalUserDetailsServiceTest {

	private final UserJpaRepository users = mock(UserJpaRepository.class);
	private final FinalUserDetailsService service = new FinalUserDetailsService(users);

	@Test
	void loadsOnlyByNormalizedLoginAndMapsIdentityActivationAndAuthorities() {
		UserEntity entity = mock(UserEntity.class);
		when(entity.getId()).thenReturn(42L);
		when(entity.getLogin()).thenReturn("alice");
		when(entity.getPasswordHash()).thenReturn("test-only-encoded-hash");
		when(entity.isActivated()).thenReturn(true);
		when(entity.getAuthorities()).thenReturn(Set.of(new AuthorityEntity("ROLE_USER")));
		when(users.findByLoginIgnoreCase("alice")).thenReturn(Optional.of(entity));
		FinalUserPrincipal principal = service.loadUserByUsername("ALICE");
		assertThat(principal.userId()).isEqualTo(42);
		assertThat(principal.getUsername()).isEqualTo("alice");
		assertThat(principal.isEnabled()).isTrue();
		assertThat(principal.getAuthorities()).extracting("authority").containsExactly("ROLE_USER");
		assertThat(principal.getPassword()).isEqualTo("test-only-encoded-hash");
		assertThat(principal.toString()).isEqualTo("FinalUserPrincipal[redacted]");
		principal.eraseCredentials();
		assertThat(principal.getPassword()).isNull();
		assertThat(principal.userId()).isEqualTo(42);
		verify(users).findByLoginIgnoreCase("alice");
	}

	@Test
	void representsInactiveUsersAsDisabledForTheAuthenticationProvider() {
		UserEntity entity = mock(UserEntity.class);
		when(entity.getId()).thenReturn(42L);
		when(entity.getLogin()).thenReturn("alice");
		when(entity.getPasswordHash()).thenReturn("test-only-encoded-hash");
		when(entity.getAuthorities()).thenReturn(Set.of());
		when(users.findByLoginIgnoreCase("alice")).thenReturn(Optional.of(entity));
		assertThat(service.loadUserByUsername("alice").isEnabled()).isFalse();
	}

	@Test
	void unknownUserDoesNotExposeItsLogin() {
		assertThatThrownBy(() -> service.loadUserByUsername("private-test-login"))
				.isInstanceOf(UsernameNotFoundException.class).hasMessage("Invalid credentials");
	}

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = {" ", "\t"})
	void rejectsMissingOrBlankLoginWithoutQueryingPersistence(String login) {
		assertThatThrownBy(() -> service.loadUserByUsername(login))
				.isInstanceOf(UsernameNotFoundException.class).hasMessage("Invalid credentials");
		verifyNoInteractions(users);
	}
}
