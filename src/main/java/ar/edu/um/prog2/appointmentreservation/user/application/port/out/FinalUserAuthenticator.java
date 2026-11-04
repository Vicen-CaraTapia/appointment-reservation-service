package ar.edu.um.prog2.appointmentreservation.user.application.port.out;

import ar.edu.um.prog2.appointmentreservation.user.application.model.AuthenticationToken;

public interface FinalUserAuthenticator {
	AuthenticationToken authenticate(String login, String password, boolean rememberMe);
}
