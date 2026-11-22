package ar.edu.um.prog2.appointmentreservation.professional.application.port.out;

import ar.edu.um.prog2.appointmentreservation.professional.application.model.CurrentProfessional;

/** Loads current information; absence or lookup failure is reported as ProfessionalLookupException. */
@FunctionalInterface
public interface CurrentProfessionalSource {
	CurrentProfessional load(long professionalId);
}
