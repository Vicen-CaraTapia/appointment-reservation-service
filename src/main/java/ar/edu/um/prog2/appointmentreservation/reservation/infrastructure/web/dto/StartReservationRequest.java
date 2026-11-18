package ar.edu.um.prog2.appointmentreservation.reservation.infrastructure.web.dto;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

import com.fasterxml.jackson.annotation.JsonAnySetter;

import jakarta.validation.constraints.NotNull;

import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.annotation.JsonDeserialize;

/** Public selection only; the authenticated owner and patient identity are supplied by the backend. */
public record StartReservationRequest(
		@NotNull(message = "Professional ID is required")
		@JsonDeserialize(using = StrictInt64Deserializer.class)
		Long professionalId,

		@NotNull(message = "Appointment date is required")
		@JsonDeserialize(using = IsoDateDeserializer.class)
		LocalDate date,

		@NotNull(message = "Appointment start time is required")
		@JsonDeserialize(using = IsoTimeDeserializer.class)
		LocalTime startTime
) {

	@JsonAnySetter
	public void rejectUnknownProperty(String name, Object value) {
		// Reject independently of global Jackson settings without echoing names or values.
		throw new IllegalArgumentException("Unknown reservation-start property");
	}

	@Override
	public String toString() {
		return "StartReservationRequest[redacted]";
	}

	static final class StrictInt64Deserializer extends ValueDeserializer<Long> {
		@Override
		public Long deserialize(JsonParser parser, DeserializationContext context) {
			if (parser.hasToken(JsonToken.VALUE_NUMBER_INT)) {
				try {
					return parser.getBigIntegerValue().longValueExact();
				} catch (ArithmeticException failure) {
					// Do not expose the rejected number or truncate an overflowing ID.
				}
			}
			return context.reportInputMismatch(Long.class, "Professional ID must be a JSON int64 integer");
		}
	}

	static final class IsoDateDeserializer extends ValueDeserializer<LocalDate> {
		@Override
		public LocalDate deserialize(JsonParser parser, DeserializationContext context) {
			if (parser.hasToken(JsonToken.VALUE_STRING)) {
				try {
					return LocalDate.parse(parser.getString(), DateTimeFormatter.ISO_LOCAL_DATE);
				} catch (DateTimeParseException failure) {
					// Do not retain the parser exception or echo the input.
				}
			}
			return context.reportInputMismatch(LocalDate.class, "Appointment date must be an ISO local-date string");
		}
	}

	static final class IsoTimeDeserializer extends ValueDeserializer<LocalTime> {
		@Override
		public LocalTime deserialize(JsonParser parser, DeserializationContext context) {
			if (parser.hasToken(JsonToken.VALUE_STRING)) {
				try {
					return LocalTime.parse(parser.getString(), DateTimeFormatter.ISO_LOCAL_TIME);
				} catch (DateTimeParseException failure) {
					// A timestamp, offset or invalid local time must not become another selected slot.
				}
			}
			return context.reportInputMismatch(LocalTime.class, "Appointment time must be an ISO local-time string without date or offset");
		}
	}
}
