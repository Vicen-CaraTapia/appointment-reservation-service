package ar.edu.um.prog2.appointmentreservation.user.infrastructure.security;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("appointment.security.final-user-jwt")
public final class FinalUserJwtProperties {

	public static final String ISSUER = "appointment-reservation-service";
	public static final String AUDIENCE = "turnos-kmp-app";

	private String base64Secret = "";
	private Duration ttl = Duration.ofHours(1);
	private Duration rememberMeTtl = Duration.ofDays(7);

	public String getBase64Secret() { return base64Secret; }
	public void setBase64Secret(String base64Secret) { this.base64Secret = base64Secret; }
	public Duration getTtl() { return ttl; }
	public void setTtl(Duration ttl) { this.ttl = ttl; }
	public Duration getRememberMeTtl() { return rememberMeTtl; }
	public void setRememberMeTtl(Duration rememberMeTtl) { this.rememberMeTtl = rememberMeTtl; }

	@Override
	public String toString() {
		return "FinalUserJwtProperties[redacted]";
	}
}
