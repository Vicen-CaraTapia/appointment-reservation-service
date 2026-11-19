package ar.edu.um.prog2.appointmentreservation.reservation.infrastructure.security;

import java.math.BigDecimal;
import java.util.Locale;

import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

import ar.edu.um.prog2.appointmentreservation.reservation.application.exception.ReservationAuthenticationRequiredException;
import ar.edu.um.prog2.appointmentreservation.reservation.application.model.AuthenticatedReservationUser;
import ar.edu.um.prog2.appointmentreservation.reservation.application.port.out.CurrentReservationUser;

/** Reads only the trusted Bearer authentication established by the final-user security chain. */
@Component
public final class SpringCurrentReservationUser implements CurrentReservationUser {

	@Override
	public AuthenticatedReservationUser requireAuthenticated() {
		var authentication = SecurityContextHolder.getContext().getAuthentication();
		if (!(authentication instanceof JwtAuthenticationToken bearer) || !bearer.isAuthenticated()) {
			throw new ReservationAuthenticationRequiredException();
		}
		var claims = bearer.getToken().getClaims();
		if (!(claims.get("userId") instanceof Number userId) || !(claims.get("sub") instanceof String login)) {
			throw new ReservationAuthenticationRequiredException();
		}
		try {
			// Exact conversion matches A2.4; never truncate a fractional or overflowing claim.
			long id = new BigDecimal(userId.toString()).longValueExact();
			return new AuthenticatedReservationUser(id, login.toLowerCase(Locale.ROOT));
		} catch (IllegalArgumentException | ArithmeticException failure) {
			// Do not retain claim values, token contents or parser causes in the application error.
			throw new ReservationAuthenticationRequiredException();
		}
	}
}
