package ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.rest.client;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Objects;

import org.jspecify.annotations.Nullable;
import org.springframework.web.util.UriUtils;
import tools.jackson.databind.JsonNode;
import ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.rest.dto.CatedraReservationCancellationResponse;
import ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.rest.exception.CatedraProtocolException;

public final class CatedraReservationCancellationClient {
	private final CatedraRestClient rest;

	public CatedraReservationCancellationClient(CatedraRestClient rest) {
		this.rest = Objects.requireNonNull(rest);
	}

	public CatedraReservationCancellationResponse cancel(String reservationProcessId, @Nullable String reason) {
		if (reservationProcessId == null || reservationProcessId.isBlank()) {
			throw new IllegalArgumentException("Reservation process identifier must be nonblank");
		}
		if (reason != null && reason.length() > 500) {
			throw new IllegalArgumentException("Cancellation reason must not exceed 500 characters");
		}
		// The external ID is one opaque segment, not a path, template or pre-encoded URL.
		String segment = UriUtils.encode(reservationProcessId, StandardCharsets.UTF_8).replace(".", "%2E");
		var request = reason == null ? null : new CancellationRequest(reason);
		var body = rest.postOk("/api/appointments/" + segment + "/cancel", request, JsonNode.class);
		try {
			if (body == null || !body.isObject()) { throw new CatedraProtocolException(); }
			var id = body.get("reservationId");
			if (id == null || !id.isIntegralNumber() || !id.canConvertToLong()) { throw new CatedraProtocolException(); }
			var response = new CatedraReservationCancellationResponse(id.asLong(), text(body, "reservationProcessId"),
					text(body, "status"), Instant.parse(text(body, "cancelledAt")));
			if (!reservationProcessId.equals(response.reservationProcessId()) || !"CANCELLED".equals(response.status())) {
				throw new CatedraProtocolException();
			}
			return response;
		} catch (DateTimeParseException | IllegalArgumentException invalidResponse) {
			throw new CatedraProtocolException();
		}
	}

	private static String text(JsonNode body, String field) {
		var value = body.get(field);
		if (value == null || !value.isTextual() || value.asString().isBlank()) { throw new CatedraProtocolException(); }
		return value.asString();
	}

	private record CancellationRequest(String reason) {
		@Override
		public String toString() { return "CancellationRequest[redacted]"; }
	}
}
