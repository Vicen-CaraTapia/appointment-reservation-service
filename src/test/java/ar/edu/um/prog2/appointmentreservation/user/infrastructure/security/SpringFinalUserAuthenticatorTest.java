package ar.edu.um.prog2.appointmentreservation.user.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.ProviderNotFoundException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import ar.edu.um.prog2.appointmentreservation.user.application.exception.AuthenticationUnavailableException;
import ar.edu.um.prog2.appointmentreservation.user.application.exception.InvalidCredentialsException;
import ar.edu.um.prog2.appointmentreservation.user.application.model.AuthenticationToken;
import org.springframework.security.oauth2.jwt.JwtEncodingException;

class SpringFinalUserAuthenticatorTest {
	private final AuthenticationManager manager = mock(AuthenticationManager.class);
	private final NimbusFinalUserTokenIssuer tokens = mock(NimbusFinalUserTokenIssuer.class);
	private final SpringFinalUserAuthenticator adapter = new SpringFinalUserAuthenticator(manager, tokens);

	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	void issuesOnlyForTheVerifiedPrincipalAndClearsSubmittedCredentials(boolean rememberMe) {
		var principal = new FinalUserPrincipal(42, "alice", "test-only-hash", true,
				List.of(new SimpleGrantedAuthority("ROLE_USER")));
		when(manager.authenticate(any())).thenReturn(UsernamePasswordAuthenticationToken.authenticated(
				principal, null, List.of(new SimpleGrantedAuthority("ROLE_USER"), new SimpleGrantedAuthority("FACTOR_PASSWORD"))));
		var token = new AuthenticationToken("test-only-token");
		when(tokens.issue(principal, rememberMe)).thenReturn(token);
		assertThat(adapter.authenticate("alice", "private-test-password", rememberMe)).isSameAs(token);
		verify(tokens).issue(org.mockito.ArgumentMatchers.same(principal), org.mockito.ArgumentMatchers.eq(rememberMe));
		assertThat(principal.getAuthorities()).extracting("authority").containsExactly("ROLE_USER");
		assertSubmittedCredentialsErased();
	}

	@ParameterizedTest
	@MethodSource("invalidCredentials")
	void hidesCredentialFailureDetailsAndClearsCredentials(AuthenticationException failure) {
		when(manager.authenticate(any())).thenThrow(failure);
		assertThatThrownBy(() -> adapter.authenticate("alice", "private-test-password", false))
				.isInstanceOf(InvalidCredentialsException.class).hasMessage("Invalid credentials").hasNoCause();
		assertSubmittedCredentialsErased();
		verifyNoInteractions(tokens);
	}

	static Stream<AuthenticationException> invalidCredentials() {
		return Stream.of(new BadCredentialsException("private diagnostic"),
				new DisabledException("private diagnostic"), new UsernameNotFoundException("private diagnostic"));
	}

	@ParameterizedTest
	@MethodSource("serviceFailures")
	void doesNotMisclassifyServiceFailuresAsInvalidCredentials(AuthenticationException failure) {
		when(manager.authenticate(any())).thenThrow(failure);
		assertThatThrownBy(() -> adapter.authenticate("alice", "private-test-password", false))
				.isInstanceOf(AuthenticationUnavailableException.class).hasNoCause();
		assertSubmittedCredentialsErased();
		verifyNoInteractions(tokens);
	}

	static Stream<AuthenticationException> serviceFailures() {
		return Stream.of(new AuthenticationServiceException("private diagnostic"),
				new ProviderNotFoundException("private diagnostic"));
	}

	@Test
	void rejectsAnUnverifiedAuthenticationResult() {
		when(manager.authenticate(any())).thenReturn(UsernamePasswordAuthenticationToken.unauthenticated("alice", null));
		assertThatThrownBy(() -> adapter.authenticate("alice", "password", false))
				.isInstanceOf(AuthenticationUnavailableException.class);
		verifyNoInteractions(tokens);
	}

	@Test
	void rejectsAPrincipalFromAnotherIdentityBoundary() {
		when(manager.authenticate(any())).thenReturn(UsernamePasswordAuthenticationToken.authenticated(
				"technical-identity", null, List.of()));
		assertThatThrownBy(() -> adapter.authenticate("alice", "password", false))
				.isInstanceOf(AuthenticationUnavailableException.class);
		verifyNoInteractions(tokens);
	}

	@Test
	void clearsSubmittedCredentialsEvenIfTokenIssuanceFails() {
		var principal = new FinalUserPrincipal(42, "alice", "test-only-hash", true, List.of());
		when(manager.authenticate(any())).thenReturn(UsernamePasswordAuthenticationToken.authenticated(principal, null, List.of()));
		JwtEncodingException failure = new JwtEncodingException("Test signing failure");
		when(tokens.issue(principal, false)).thenThrow(failure);
		assertThatThrownBy(() -> adapter.authenticate("alice", "password", false)).isSameAs(failure);
		assertSubmittedCredentialsErased();
	}

	private void assertSubmittedCredentialsErased() {
		ArgumentCaptor<Authentication> request = ArgumentCaptor.forClass(Authentication.class);
		verify(manager).authenticate(request.capture());
		assertThat(request.getValue().getCredentials()).isNull();
	}
}
