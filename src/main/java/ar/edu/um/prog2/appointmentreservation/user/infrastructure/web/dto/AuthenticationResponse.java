package ar.edu.um.prog2.appointmentreservation.user.infrastructure.web.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record AuthenticationResponse(@JsonProperty("id_token") String idToken) {
	@Override
	public String toString() { return "AuthenticationResponse[redacted]"; }
}
