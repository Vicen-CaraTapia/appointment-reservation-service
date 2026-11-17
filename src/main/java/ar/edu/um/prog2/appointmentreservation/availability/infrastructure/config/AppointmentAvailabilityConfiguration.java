package ar.edu.um.prog2.appointmentreservation.availability.infrastructure.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import ar.edu.um.prog2.appointmentreservation.availability.application.port.out.CurrentOccupancySource;
import ar.edu.um.prog2.appointmentreservation.availability.application.service.AvailableAppointmentSlotsCalculator;
import ar.edu.um.prog2.appointmentreservation.availability.application.usecase.CalculateAppointmentAvailabilityUseCase;
import ar.edu.um.prog2.appointmentreservation.professional.application.usecase.LoadEnabledProfessionalUseCase;

@Configuration(proxyBeanMethods = false)
public class AppointmentAvailabilityConfiguration {

	@Bean
	public CalculateAppointmentAvailabilityUseCase calculateAppointmentAvailabilityUseCase(
			LoadEnabledProfessionalUseCase professionals, CurrentOccupancySource occupancies) {
		return new CalculateAppointmentAvailabilityUseCase(professionals, occupancies,
				new AvailableAppointmentSlotsCalculator());
	}
}
