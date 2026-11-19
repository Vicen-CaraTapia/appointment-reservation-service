package ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.rest.client;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Objects;

import tools.jackson.databind.JsonNode;

import ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.rest.dto.CatedraAppointmentHoldRequest;
import ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.rest.dto.CatedraAppointmentHoldResponse;
import ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.rest.exception.CatedraHoldException;
import ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.rest.exception.CatedraHttpException;
import ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.rest.exception.CatedraProtocolException;

public final class CatedraAppointmentHoldClient {

	private final CatedraRestClient rest;

	public CatedraAppointmentHoldClient(CatedraRestClient rest) {
		this.rest = rest;
	}

	public CatedraAppointmentHoldResponse createHold(CatedraAppointmentHoldRequest request) {
		Objects.requireNonNull(request, "Hold request is required");
		try {
			var response = rest.postCreated("/api/appointment-holds", request, JsonNode.class);
			if (response == null || !response.isObject()) {
				throw new CatedraProtocolException();
			}
			// Check wire types before constructing the DTO; do not coerce numeric IDs or timestamps.
			return new CatedraAppointmentHoldResponse(text(response, "holdId"), text(response, "reservationProcessId"),
					text(response, "status"), Instant.parse(text(response, "expiresAt")));
		} catch (CatedraHttpException failure) {
			for (var reason : CatedraHoldException.Reason.values()) {
				if (reason.httpStatus() == failure.status().value() && reason.name().equals(failure.code())) {
					throw new CatedraHoldException(reason);
				}
			}
			throw failure;
		} catch (DateTimeParseException | IllegalArgumentException failure) {
			// Parser and constructor messages may include remote values; do not preserve their causes.
			throw new CatedraProtocolException();
		}
	}

	private static String text(JsonNode response, String field) {
		var value = response.get(field);
		if (value == null || !value.isTextual()) {
			throw new CatedraProtocolException();
		}
		return value.asString();
	}
}
