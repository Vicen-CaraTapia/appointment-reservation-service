package ar.edu.um.prog2.appointmentreservation.user.infrastructure.web.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Public authentication input; username identifies the final user's login. */
public final class AuthenticationRequest {

	@NotBlank(message = "Username must not be blank")
	@Size(min = 1, max = 254, message = "Username must contain between 1 and 254 characters")
	@JsonProperty("username")
	private String username;

	@NotBlank(message = "Password must not be blank")
	@Size(min = 4, max = 100, message = "Password must contain between 4 and 100 characters")
	@JsonProperty(value = "password", access = JsonProperty.Access.WRITE_ONLY)
	private String password;

	@JsonProperty("rememberMe")
	@JsonSetter(nulls = Nulls.FAIL)
	private boolean rememberMe;

	// Field binding distinguishes an omitted primitive (false) from explicit null.
	public AuthenticationRequest() {
	}

	@JsonCreator(mode = JsonCreator.Mode.DISABLED)
	public AuthenticationRequest(String username, String password, boolean rememberMe) {
		this.username = username;
		this.password = password;
		this.rememberMe = rememberMe;
	}

	public String username() {
		return username;
	}

	public String password() {
		return password;
	}

	public boolean rememberMe() {
		return rememberMe;
	}

	@JsonAnySetter
	public void rejectUnknownProperty(String name, Object value) {
		// Do not echo names or values: either may contain sensitive input.
		throw new IllegalArgumentException("Unknown authentication property");
	}

	@Override
	public String toString() {
		return "AuthenticationRequest[redacted]";
	}
}
