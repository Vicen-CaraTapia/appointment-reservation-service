package ar.edu.um.prog2.appointmentreservation.reservation.infrastructure.web.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Size;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.annotation.JsonDeserialize;

/** Optional user text, never trusted routing or ownership information. */
public record CancelReservationRequest(
        @Size(max = 500) @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
        @JsonDeserialize(using = StrictString.class) String reason) {
    @JsonAnySetter
    public void rejectUnknownProperty(String name, Object value) {
        throw new IllegalArgumentException("Unsupported cancellation property");
    }
    @Override public String toString() { return "CancelReservationRequest[redacted]"; }
    static final class StrictString extends ValueDeserializer<String> {
        @Override public String deserialize(JsonParser parser, DeserializationContext context) {
            if (parser.hasToken(JsonToken.VALUE_STRING)) { return parser.getString(); }
            return context.reportInputMismatch(String.class, "Cancellation reason must be a JSON string");
        }
    }
}
