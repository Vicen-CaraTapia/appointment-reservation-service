package ar.edu.um.prog2.appointmentreservation.reservation.infrastructure.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

import ar.edu.um.prog2.appointmentreservation.availability.application.service.AvailableAppointmentSlotsCalculator;
import ar.edu.um.prog2.appointmentreservation.professional.application.usecase.LoadEnabledProfessionalUseCase;
import ar.edu.um.prog2.appointmentreservation.reservation.application.service.ReservationSlotValidator;
import ar.edu.um.prog2.appointmentreservation.reservation.application.port.out.ReservationProcessStore;
import ar.edu.um.prog2.appointmentreservation.reservation.infrastructure.persistence.JpaReservationProcessAdapter;
import ar.edu.um.prog2.appointmentreservation.reservation.infrastructure.persistence.repository.ReservationProcessJpaRepository;
import ar.edu.um.prog2.appointmentreservation.user.infrastructure.persistence.repository.UserJpaRepository;
import ar.edu.um.prog2.appointmentreservation.reservation.application.port.out.AppointmentHoldRequester;
import ar.edu.um.prog2.appointmentreservation.reservation.application.port.out.CurrentReservationUser;
import ar.edu.um.prog2.appointmentreservation.reservation.application.usecase.StartReservationProcessUseCase;

@Configuration(proxyBeanMethods = false)
public class ReservationProcessConfiguration {

	@Bean
	public StartReservationProcessUseCase startReservationProcessUseCase(CurrentReservationUser currentUser,
			ReservationSlotValidator slots, AppointmentHoldRequester holds, ReservationProcessStore processes) {
		return new StartReservationProcessUseCase(currentUser, slots, holds, processes);
	}

	@Bean
	public ReservationProcessStore reservationProcessStore(UserJpaRepository users,
			ReservationProcessJpaRepository processes, PlatformTransactionManager transactions) {
		return new JpaReservationProcessAdapter(users, processes, transactions);
	}

	@Bean
	public ReservationSlotValidator reservationSlotValidator(LoadEnabledProfessionalUseCase professionals) {
		return new ReservationSlotValidator(professionals, new AvailableAppointmentSlotsCalculator());
	}
}
