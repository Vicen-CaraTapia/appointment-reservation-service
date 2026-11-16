package ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.rest.client;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.List;

import org.springframework.web.util.UriComponentsBuilder;

import ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.rest.dto.CatedraAppointmentOccupanciesResponse;
import ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.rest.dto.CatedraAppointmentOccupanciesResponse.Occupancy;
import ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.rest.dto.CatedraAppointmentOccupanciesResponse.Status;
import ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.rest.exception.CatedraProtocolException;

/** Read-only access to current cátedra occupancies; does not generate or reserve slots. */
public final class CatedraAppointmentOccupanciesClient {

	private final CatedraRestClient rest;

	public CatedraAppointmentOccupanciesClient(CatedraRestClient rest) {
		this.rest = rest;
	}

	public CatedraAppointmentOccupanciesResponse getOccupancies(long professionalId, LocalDate from, LocalDate to) {
		if (from == null || to == null || from.isAfter(to)) {
			throw new IllegalArgumentException("Occupancy dates are required and from must not be after to");
		}
		String path = UriComponentsBuilder.fromPath("/api/appointment-occupancies")
				.queryParam("professionalId", professionalId)
				.queryParam("from", from)
				.queryParam("to", to)
				.build().encode().toUriString();
		var response = rest.get(path, CatedraAppointmentOccupanciesResponse.class);
		if (response == null || response.professionalId() != professionalId
				|| !from.equals(response.from()) || !to.equals(response.to())) {
			throw new CatedraProtocolException();
		}
		var positions = new LinkedHashMap<Position, Occupancy>();
		for (var occupancy : response.occupancies()) {
			if (occupancy.date().isBefore(from) || occupancy.date().isAfter(to)
					|| (occupancy.status() == Status.CONFIRMED && occupancy.expiresAt() != null)) {
				throw new CatedraProtocolException();
			}
			positions.merge(new Position(occupancy.date(), occupancy.startTime()), occupancy,
					(existing, incoming) -> existing.status() == Status.CONFIRMED ? existing : incoming);
		}
		return new CatedraAppointmentOccupanciesResponse(professionalId, from, to, List.copyOf(positions.values()));
	}

	private record Position(LocalDate date, LocalTime startTime) {
	}
}
