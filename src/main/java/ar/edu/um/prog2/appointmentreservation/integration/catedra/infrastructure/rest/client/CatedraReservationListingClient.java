package ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.rest.client;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.DateTimeException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.JsonNode;
import ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.rest.dto.CatedraReservationPage;
import ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.rest.dto.CatedraReservationPage.Reservation;
import ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.rest.exception.CatedraProtocolException;
import ar.edu.um.prog2.appointmentreservation.reservation.application.model.ReservationListQuery;

/** Official technical listing only. No user authorization, local state changes or following remote links. */
public final class CatedraReservationListingClient {
	private final CatedraRestClient rest;

	public CatedraReservationListingClient(CatedraRestClient rest) {
		this.rest = Objects.requireNonNull(rest);
	}

	public CatedraReservationPage list(ReservationListQuery query) {
		Objects.requireNonNull(query, "Reservation query is required");
		var builder = UriComponentsBuilder.fromPath("/api/appointments");
		Map<String, Object> values = new LinkedHashMap<>();
		filter(builder, values, "status", query.status());
		filter(builder, values, "from", query.from());
		filter(builder, values, "to", query.to());
		filter(builder, values, "externalPatientId", query.externalPatientId());
		filter(builder, values, "page", query.page());
		filter(builder, values, "size", query.size());
		for (int i = 0; i < query.sort().size(); i++) {
			String key = "sort" + i;
			builder.queryParam("sort", "{" + key + "}");
			values.put(key, query.sort().get(i));
		}
		String path = builder.encode().buildAndExpand(values).toUriString();
		var response = rest.getOkWithPagination(path, JsonNode.class);
		try {
			if (!response.body().isArray() || response.totalCountValues().size() != 1) {
				throw new CatedraProtocolException();
			}
			String rawCount = response.totalCountValues().getFirst();
			if (!rawCount.matches("[0-9]+")) {
				throw new CatedraProtocolException();
			}
			long count = Long.parseLong(rawCount);
			var rows = new ArrayList<Reservation>();
			for (var row : response.body()) {
				var reservation = reservation(row);
				validateFilters(reservation, query);
				rows.add(reservation);
			}
			return new CatedraReservationPage(rows, count, response.linkValues());
		} catch (IllegalArgumentException | DateTimeException failure) {
			throw new CatedraProtocolException();
		}
	}
	private static Reservation reservation(JsonNode row) {
		if (!row.isObject()) {
			throw new CatedraProtocolException();
		}
		var reservation = new Reservation(number(row, "reservationId"), text(row, "reservationProcessId"),
				text(row, "externalPatientId"), LocalDate.parse(text(row, "appointmentDate")),
				LocalTime.parse(text(row, "startTime")), LocalTime.parse(text(row, "endTime")),
				number(row, "professionalId"), nullableText(row, "professionalFirstName"), nullableText(row, "professionalLastName"),
				nullableText(row, "patientFirstName"), nullableText(row, "patientLastName"), nullableText(row, "patientPhone"),
				text(row, "status"), Instant.parse(text(row, "createdAt")), nullableInstant(row, "confirmedAt"),
				nullableInstant(row, "cancelledAt"), nullableText(row, "cancellationReason"));
		if (!Set.of("PHONE_PENDING", "CONFIRMED", "CANCELLED", "FAILED").contains(reservation.status())) {
			throw new CatedraProtocolException();
		}
		return reservation;
	}

	private static void validateFilters(Reservation reservation, ReservationListQuery query) {
		if ((query.status() != null && !query.status().name().equals(reservation.status()))
				|| (query.from() != null && reservation.appointmentDate().isBefore(query.from()))
				|| (query.to() != null && reservation.appointmentDate().isAfter(query.to()))
				|| (query.externalPatientId() != null && !query.externalPatientId().equals(reservation.externalPatientId()))) {
			throw new CatedraProtocolException();
		}
	}

	private static void filter(UriComponentsBuilder builder, Map<String, Object> values, String name, Object value) {
		if (value != null) {
			builder.queryParam(name, "{" + name + "}");
			values.put(name, value);
		}
	}

	private static String text(JsonNode row, String name) {
		var value = row.get(name);
		if (value == null || !value.isTextual() || value.asText().isBlank()) {
			throw new CatedraProtocolException();
		}
		return value.asText();
	}

	private static String nullableText(JsonNode row, String name) {
		var value = row.get(name);
		if (value == null || value.isNull()) {
			return null;
		}
		if (!value.isTextual()) {
			throw new CatedraProtocolException();
		}
		return value.asText();
	}

	private static Instant nullableInstant(JsonNode row, String name) {
		var value = nullableText(row, name);
		return value == null ? null : Instant.parse(value);
	}

	private static long number(JsonNode row, String name) {
		var value = row.get(name);
		if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) {
			throw new CatedraProtocolException();
		}
		return value.asLong();
	}
}
