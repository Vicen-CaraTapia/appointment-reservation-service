package ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.rest.client;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Objects;

import org.springframework.web.util.UriUtils;
import tools.jackson.databind.JsonNode;

import ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.rest.dto.CatedraAppointmentHoldConfirmationRequest;
import ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.rest.dto.CatedraAppointmentHoldConfirmationResponse;
import ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.rest.exception.CatedraHoldConfirmationException;
import ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.rest.exception.CatedraHttpException;
import ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.rest.exception.CatedraProtocolException;

public final class CatedraAppointmentHoldConfirmationClient {

	private final CatedraRestClient rest;

	public CatedraAppointmentHoldConfirmationClient(CatedraRestClient rest) {
		this.rest = Objects.requireNonNull(rest);
	}

	public CatedraAppointmentHoldConfirmationResponse confirm(String holdId, String expectedReservationProcessId,
			CatedraAppointmentHoldConfirmationRequest request) {
		Objects.requireNonNull(request, "Initial confirmation request is required");
		if (holdId == null || holdId.isBlank() || expectedReservationProcessId == null
				|| expectedReservationProcessId.isBlank()) {
			throw new IllegalArgumentException("Hold and expected process identifiers must be nonblank");
		}
		// Encode once as a single segment, including dots to prevent dot-segment traversal.
		String segment = UriUtils.encodePathSegment(holdId, StandardCharsets.UTF_8).replace(".", "%2E");
		String path = "/api/appointment-holds/" + segment + "/confirm";
		try {
			var body = rest.postAccepted(path, request, JsonNode.class);
			if (body == null || !body.isObject()) {
				throw new CatedraProtocolException();
			}
			var result = new CatedraAppointmentHoldConfirmationResponse(text(body, "reservationProcessId"),
					text(body, "status"), Instant.parse(text(body, "expiresAt")));
			if (!expectedReservationProcessId.equals(result.reservationProcessId())) {
				throw new CatedraProtocolException();
			}
			return result;
		} catch (CatedraHttpException failure) {
			for (var reason : CatedraHoldConfirmationException.Reason.values()) {
				if (reason.httpStatus() == failure.status().value() && reason.name().equals(failure.code())) {
					throw new CatedraHoldConfirmationException(reason);
				}
			}
			throw failure;
		} catch (DateTimeParseException | IllegalArgumentException failure) {
			// External parser/DTO diagnostics may contain personal information or credentials.
			throw new CatedraProtocolException();
		}
	}

	private static String text(JsonNode body, String field) {
		var value = body.get(field);
		if (value == null || !value.isTextual()) {
			throw new CatedraProtocolException();
		}
		return value.asString();
	}
}
