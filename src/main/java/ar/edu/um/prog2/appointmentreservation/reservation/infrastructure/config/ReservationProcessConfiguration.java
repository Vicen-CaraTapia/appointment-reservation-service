package ar.edu.um.prog2.appointmentreservation.reservation.infrastructure.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import ar.edu.um.prog2.appointmentreservation.availability.application.service.AvailableAppointmentSlotsCalculator;
import ar.edu.um.prog2.appointmentreservation.professional.application.usecase.LoadEnabledProfessionalUseCase;
import ar.edu.um.prog2.appointmentreservation.reservation.application.service.ReservationSlotValidator;

@Configuration(proxyBeanMethods = false)
public class ReservationProcessConfiguration {

	@Bean
	public ReservationSlotValidator reservationSlotValidator(LoadEnabledProfessionalUseCase professionals) {
		return new ReservationSlotValidator(professionals, new AvailableAppointmentSlotsCalculator());
	}
}
