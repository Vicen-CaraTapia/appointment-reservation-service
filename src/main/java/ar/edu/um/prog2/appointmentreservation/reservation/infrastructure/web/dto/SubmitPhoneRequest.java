package ar.edu.um.prog2.appointmentreservation.reservation.infrastructure.web.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.annotation.JsonDeserialize;

/** Personal input only. Normalization belongs to the application use case. */
public record SubmitPhoneRequest(
		@NotBlank(message = "Phone number is required")
		@JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
		@JsonDeserialize(using = StrictStringDeserializer.class) String phoneNumber) {
	@JsonAnySetter
	public void rejectUnknownProperty(String name, Object value) {
		throw new IllegalArgumentException("Unknown phone submission property");
	}
	@Override
	public String toString() { return "SubmitPhoneRequest[redacted]"; }
	static final class StrictStringDeserializer extends ValueDeserializer<String> {
		@Override
		public String deserialize(JsonParser parser, DeserializationContext context) {
			if (parser.hasToken(JsonToken.VALUE_STRING)) { return parser.getString(); }
			return context.reportInputMismatch(String.class, "Phone number must be a JSON string");
		}
	}
}
