package ar.edu.um.prog2.appointmentreservation.reservation.infrastructure.web.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.annotation.JsonDeserialize;

/** Public patient input only; owner and technical identifiers come from the backend. */
public record ConfirmReservationRequest(
		@NotBlank(message = "Patient first name is required")
		@Size(min = 2, max = 100, message = "Patient first name must contain 2 to 100 characters")
		@JsonDeserialize(using = StrictStringDeserializer.class)
		String patientFirstName,

		@NotBlank(message = "Patient last name is required")
		@Size(min = 2, max = 100, message = "Patient last name must contain 2 to 100 characters")
		@JsonDeserialize(using = StrictStringDeserializer.class)
		String patientLastName
) {

	@JsonAnySetter
	public void rejectUnknownProperty(String name, Object value) {
		throw new IllegalArgumentException("Unknown reservation-confirmation property");
	}

	@Override
	public String toString() {
		return "ConfirmReservationRequest[redacted]";
	}

	static final class StrictStringDeserializer extends ValueDeserializer<String> {
		@Override
		public String deserialize(JsonParser parser, DeserializationContext context) {
			if (parser.hasToken(JsonToken.VALUE_STRING)) {
				return parser.getString();
			}
			return context.reportInputMismatch(String.class, "Patient name must be a JSON string");
		}
	}
}
