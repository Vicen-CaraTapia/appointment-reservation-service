package ar.edu.um.prog2.appointmentreservation.user.infrastructure.security;

import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.authentication.ProviderNotFoundException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.AuthenticationException;

import ar.edu.um.prog2.appointmentreservation.user.application.exception.AuthenticationUnavailableException;
import ar.edu.um.prog2.appointmentreservation.user.application.exception.InvalidCredentialsException;
import ar.edu.um.prog2.appointmentreservation.user.application.model.AuthenticationToken;
import ar.edu.um.prog2.appointmentreservation.user.application.port.out.FinalUserAuthenticator;

public final class SpringFinalUserAuthenticator implements FinalUserAuthenticator {
	private final AuthenticationManager manager;
	private final NimbusFinalUserTokenIssuer tokens;

	public SpringFinalUserAuthenticator(AuthenticationManager manager, NimbusFinalUserTokenIssuer tokens) {
		this.manager = manager;
		this.tokens = tokens;
	}

	@Override
	public AuthenticationToken authenticate(String login, String password, boolean rememberMe) {
		var credentials = UsernamePasswordAuthenticationToken.unauthenticated(login, password);
		try {
			var authentication = manager.authenticate(credentials);
			if (!authentication.isAuthenticated() || !(authentication.getPrincipal() instanceof FinalUserPrincipal user)) {
				throw new AuthenticationUnavailableException();
			}
			return tokens.issue(user, rememberMe);
		}
		catch (AuthenticationServiceException | ProviderNotFoundException failure) {
			throw new AuthenticationUnavailableException();
		}
		catch (AuthenticationException failure) {
			// Do not retain framework messages or causes in public application failures.
			throw new InvalidCredentialsException();
		}
		finally {
			credentials.eraseCredentials();
		}
	}
}
