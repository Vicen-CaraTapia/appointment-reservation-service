package ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.rest.adapter;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Set;
import java.util.stream.Collectors;

import ar.edu.um.prog2.appointmentreservation.availability.application.exception.OccupancyLookupException;
import ar.edu.um.prog2.appointmentreservation.availability.application.exception.OccupancyLookupException.Reason;
import ar.edu.um.prog2.appointmentreservation.availability.application.port.out.CurrentOccupancySource;
import ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.rest.client.CatedraAppointmentOccupanciesClient;
import ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.rest.exception.CatedraHttpException;
import ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.rest.exception.CatedraRestException;
import ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.rest.exception.CatedraTimeoutException;
import ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.rest.exception.CatedraTransportException;

public final class CatedraCurrentOccupancyAdapter implements CurrentOccupancySource {

	private final CatedraAppointmentOccupanciesClient client;

	public CatedraCurrentOccupancyAdapter(CatedraAppointmentOccupanciesClient client) {
		this.client = client;
	}

	@Override
	public Set<LocalTime> loadBlockedStartTimes(long professionalId, LocalDate date) {
		try {
			// Both statuses returned by the validated client block the position. The cátedra decides hold expiry.
			return client.getOccupancies(professionalId, date, date).occupancies().stream()
					.map(occupancy -> occupancy.startTime()).collect(Collectors.toUnmodifiableSet());
		} catch (CatedraRestException failure) {
			throw new OccupancyLookupException(reason(failure));
		}
	}

	private static Reason reason(CatedraRestException failure) {
		if (failure instanceof CatedraTimeoutException) {
			return Reason.TIMEOUT;
		}
		if (failure instanceof CatedraTransportException) {
			return Reason.UNAVAILABLE;
		}
		if (failure instanceof CatedraHttpException http) {
			int status = http.status().value();
			if (status >= 500 && status <= 599) {
				return Reason.UNAVAILABLE;
			}
			return switch (status) {
				case 401 -> Reason.UNAUTHORIZED;
				case 403 -> Reason.FORBIDDEN;
				case 404 -> Reason.NOT_FOUND;
				case 409 -> Reason.CONFLICT;
				default -> status >= 400 && status <= 499 ? Reason.REQUEST_REJECTED : Reason.INVALID_RESPONSE;
			};
		}
		return Reason.INVALID_RESPONSE;
	}
}
