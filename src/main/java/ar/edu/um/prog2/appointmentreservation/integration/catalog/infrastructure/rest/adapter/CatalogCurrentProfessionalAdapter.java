package ar.edu.um.prog2.appointmentreservation.integration.catalog.infrastructure.rest.adapter;

import ar.edu.um.prog2.appointmentreservation.integration.catalog.infrastructure.rest.client.CatalogProfessionalClient;
import ar.edu.um.prog2.appointmentreservation.integration.catalog.infrastructure.rest.exception.CatalogClientException;
import ar.edu.um.prog2.appointmentreservation.professional.application.exception.ProfessionalLookupException;
import ar.edu.um.prog2.appointmentreservation.professional.application.exception.ProfessionalLookupException.Reason;
import ar.edu.um.prog2.appointmentreservation.professional.application.model.CurrentProfessional;
import ar.edu.um.prog2.appointmentreservation.professional.application.port.out.CurrentProfessionalSource;

public final class CatalogCurrentProfessionalAdapter implements CurrentProfessionalSource {

	private final CatalogProfessionalClient client;

	public CatalogCurrentProfessionalAdapter(CatalogProfessionalClient client) {
		this.client = client;
	}

	@Override
	public CurrentProfessional load(long professionalId) {
		try {
			var professional = client.getProfessional(professionalId);
			var category = professional.category();
			var schedules = professional.weeklySchedules().stream().map(schedule -> new CurrentProfessional.WeeklySchedule(
					schedule.id(), schedule.dayOfWeek(), schedule.startTime(), schedule.endTime(),
					schedule.slotDurationMinutes(), schedule.enabled())).toList();
			return new CurrentProfessional(professional.id(), professional.firstName(), professional.lastName(), professional.enabled(),
					new CurrentProfessional.Category(category.id(), category.name(), category.enabled()), schedules);
		} catch (CatalogClientException failure) {
			throw new ProfessionalLookupException(reason(failure));
		}
	}

	private static Reason reason(CatalogClientException failure) {
		return switch (failure.reason()) {
			case TIMEOUT -> Reason.CATALOG_TIMEOUT;
			case TRANSPORT -> Reason.CATALOG_UNAVAILABLE;
			case INVALID_RESPONSE -> Reason.INVALID_CATALOG_RESPONSE;
			case HTTP -> httpReason(failure.status().orElseThrow());
		};
	}

	private static Reason httpReason(int status) {
		return switch (status) {
			case 401 -> Reason.CATALOG_UNAUTHORIZED;
			case 403 -> Reason.CATALOG_FORBIDDEN;
			case 404 -> Reason.NOT_FOUND;
			default -> status >= 500 && status <= 599 ? Reason.CATALOG_UNAVAILABLE : Reason.INVALID_CATALOG_RESPONSE;
		};
	}
}
