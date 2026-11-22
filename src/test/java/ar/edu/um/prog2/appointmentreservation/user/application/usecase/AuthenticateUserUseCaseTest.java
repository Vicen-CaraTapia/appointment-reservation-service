package ar.edu.um.prog2.appointmentreservation.user.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import ar.edu.um.prog2.appointmentreservation.user.application.exception.AuthenticationUnavailableException;
import ar.edu.um.prog2.appointmentreservation.user.application.exception.InvalidCredentialsException;
import ar.edu.um.prog2.appointmentreservation.user.application.model.AuthenticationCommand;
import ar.edu.um.prog2.appointmentreservation.user.application.model.AuthenticationToken;
import ar.edu.um.prog2.appointmentreservation.user.application.port.out.FinalUserAuthenticator;

class AuthenticateUserUseCaseTest {
	private final FinalUserAuthenticator credentials = mock(FinalUserAuthenticator.class);
	private final AuthenticateUserUseCase useCase = new AuthenticateUserUseCase(credentials);

	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	void normalizesOnlyLoginAndIssuesTokenForTheVerifiedIdentity(boolean rememberMe) {
		var token = new AuthenticationToken("test-only-token");
		when(credentials.authenticate("alice", "  Test passphrase  ", rememberMe)).thenReturn(token);
		assertThat(useCase.authenticate(new AuthenticationCommand("ALICE", "  Test passphrase  ", rememberMe)))
				.isSameAs(token);
		verify(credentials).authenticate("alice", "  Test passphrase  ", rememberMe);
	}

	@Test
	void neverIssuesTokenForInvalidCredentials() {
		when(credentials.authenticate("alice", "password", false)).thenThrow(new InvalidCredentialsException());
		assertThatThrownBy(() -> useCase.authenticate(new AuthenticationCommand("alice", "password", false)))
				.isInstanceOf(InvalidCredentialsException.class);
	}

	@Test
	void neverIssuesTokenWhenAuthenticationIsUnavailable() {
		when(credentials.authenticate("alice", "password", false)).thenThrow(new AuthenticationUnavailableException());
		assertThatThrownBy(() -> useCase.authenticate(new AuthenticationCommand("alice", "password", false)))
				.isInstanceOf(AuthenticationUnavailableException.class);
	}

	@Test
	void sensitiveModelsAreRedacted() {
		assertThat(new AuthenticationCommand("alice", "private-test-password", false).toString())
				.isEqualTo("AuthenticationCommand[redacted]");
		assertThat(new AuthenticationToken("private-test-token").toString()).isEqualTo("AuthenticationToken[redacted]");
	}
}
