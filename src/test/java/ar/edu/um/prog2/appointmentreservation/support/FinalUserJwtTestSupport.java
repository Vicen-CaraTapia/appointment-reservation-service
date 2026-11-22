package ar.edu.um.prog2.appointmentreservation.support;

import java.security.SecureRandom;
import java.util.Base64;

/** Ephemeral test key only; no production signing material is loaded or stored. */
public final class FinalUserJwtTestSupport {

	public static final String SECRET_PROPERTY = "appointment.security.final-user-jwt.base64-secret";
	private static final String SECRET = generateSecret();

	private FinalUserJwtTestSupport() {
	}

	public static String signingSecret() {
		return SECRET;
	}

	public static String generateSecret() {
		byte[] bytes = new byte[64];
		new SecureRandom().nextBytes(bytes);
		return Base64.getEncoder().encodeToString(bytes);
	}
}
