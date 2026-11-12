package ar.edu.um.prog2.appointmentreservation.professional.application.usecase;

import java.util.Objects;

import ar.edu.um.prog2.appointmentreservation.professional.application.exception.ProfessionalLookupException;
import ar.edu.um.prog2.appointmentreservation.professional.application.exception.ProfessionalLookupException.Reason;
import ar.edu.um.prog2.appointmentreservation.professional.application.model.CurrentProfessional;
import ar.edu.um.prog2.appointmentreservation.professional.application.port.out.CurrentProfessionalSource;

/** Requires a successful current lookup and an enabled professional before returning data to a caller. */
public final class LoadEnabledProfessionalUseCase {

	private final CurrentProfessionalSource professionals;

	public LoadEnabledProfessionalUseCase(CurrentProfessionalSource professionals) {
		this.professionals = Objects.requireNonNull(professionals);
	}

	public CurrentProfessional load(long professionalId) {
		var professional = professionals.load(professionalId);
		if (!professional.enabled()) {
			throw new ProfessionalLookupException(Reason.DISABLED);
		}
		return professional;
	}
}
