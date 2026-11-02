package ar.edu.um.prog2.appointmentreservation.user.infrastructure.web.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonProperty;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Public registration input. Administrative state is assigned by the backend.
 * Validation follows JHipster's AdminUserDTO and ManagedUserVM field limits.
 */
public record RegisterUserRequest(
		@NotBlank
		@Size(min = 1, max = 50)
		@Pattern(regexp = LOGIN_REGEX)
		String login,

		@NotBlank
		@Size(min = 4, max = 100)
		@JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
		String password,

		@Size(max = 50)
		String firstName,

		@Size(max = 50)
		String lastName,

		@NotBlank
		@Email
		@Size(min = 5, max = 254)
		String email,

		@Size(max = 256)
		String imageUrl,

		@NotBlank
		@Size(min = 2, max = 10)
		String langKey
) {
	// JHipster generators/generator-constants.ts LOGIN_REGEX (Java form).
	private static final String LOGIN_REGEX =
			"^(?>[a-zA-Z0-9!$&*+=?^_`{|}~.-]+@[a-zA-Z0-9-]+(?:\\.[a-zA-Z0-9-]+)*)|(?>[_.@A-Za-z0-9-]+)$";

	public RegisterUserRequest {
		if (langKey == null) {
			langKey = "es";
		}
	}

	/** Reject extra input independently of global unknown-property settings. */
	@JsonAnySetter
	public void rejectUnknownProperty(String name, Object value) {
		// Neither the name nor its value is echoed: either may contain sensitive input.
		throw new IllegalArgumentException("Unknown registration property");
	}

	@Override
	public String toString() {
		return "RegisterUserRequest[redacted]";
	}
}
