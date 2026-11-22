package ar.edu.um.prog2.appointmentreservation.user.application.usecase;

import java.util.Locale;

import ar.edu.um.prog2.appointmentreservation.user.application.model.AuthenticationCommand;
import ar.edu.um.prog2.appointmentreservation.user.application.model.AuthenticationToken;
import ar.edu.um.prog2.appointmentreservation.user.application.port.out.FinalUserAuthenticator;

public final class AuthenticateUserUseCase {
	private final FinalUserAuthenticator authenticator;

	public AuthenticateUserUseCase(FinalUserAuthenticator authenticator) {
		this.authenticator = authenticator;
	}

	public AuthenticationToken authenticate(AuthenticationCommand command) {
		return authenticator.authenticate(command.username().toLowerCase(Locale.ROOT), command.password(), command.rememberMe());
	}
}
