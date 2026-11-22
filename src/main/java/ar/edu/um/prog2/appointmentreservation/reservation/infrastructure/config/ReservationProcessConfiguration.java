package ar.edu.um.prog2.appointmentreservation.reservation.infrastructure.config;

import java.time.Clock;
import ar.edu.um.prog2.appointmentreservation.reservation.application.usecase.GetReservationProcessProgressUseCase;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

import ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.config.CatedraIntegrationProperties;
import ar.edu.um.prog2.appointmentreservation.reservation.application.usecase.PreparePhoneSubmissionUseCase;
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
import ar.edu.um.prog2.appointmentreservation.reservation.application.usecase.ConfirmReservationProcessUseCase;
import ar.edu.um.prog2.appointmentreservation.reservation.application.usecase.ProcessReservationActionUseCase;
import ar.edu.um.prog2.appointmentreservation.reservation.application.port.out.AppointmentHoldConfirmer;
import ar.edu.um.prog2.appointmentreservation.reservation.application.port.out.ReservationEventStore;
import ar.edu.um.prog2.appointmentreservation.reservation.infrastructure.persistence.JpaReservationEventAdapter;
import ar.edu.um.prog2.appointmentreservation.reservation.infrastructure.persistence.repository.ReservationEventInboxJpaRepository;
import ar.edu.um.prog2.appointmentreservation.reservation.application.port.out.PhoneSubmissionStore;
import ar.edu.um.prog2.appointmentreservation.reservation.infrastructure.persistence.JpaPhoneSubmissionAdapter;
import ar.edu.um.prog2.appointmentreservation.reservation.infrastructure.persistence.repository.PhoneSubmissionJpaRepository;

import ar.edu.um.prog2.appointmentreservation.reservation.application.usecase.ListOwnedReservationsUseCase;
import ar.edu.um.prog2.appointmentreservation.reservation.application.port.out.TechnicalReservationSource;

@Configuration(proxyBeanMethods = false)
public class ReservationProcessConfiguration {

	@Bean
	public ListOwnedReservationsUseCase listOwnedReservationsUseCase(CurrentReservationUser currentUser,
			TechnicalReservationSource remote, ReservationProcessStore processes) {
		return new ListOwnedReservationsUseCase(currentUser, remote, processes);
	}


	@Bean
	public GetReservationProcessProgressUseCase getReservationProcessProgressUseCase(CurrentReservationUser currentUser,
			ReservationProcessStore processes) {
		return new GetReservationProcessProgressUseCase(currentUser, processes, Clock.systemUTC());
	}

	@Bean
	public PreparePhoneSubmissionUseCase preparePhoneSubmissionUseCase(CurrentReservationUser currentUser,
			PhoneSubmissionStore submissions, CatedraIntegrationProperties properties) {
		return new PreparePhoneSubmissionUseCase(currentUser, submissions, properties.groupId(), Clock.systemUTC());
	}

	@Bean
	public ProcessReservationActionUseCase processReservationActionUseCase(ReservationEventStore events) {
		return new ProcessReservationActionUseCase(events);
	}

	@Bean
	public PhoneSubmissionStore phoneSubmissionStore(ReservationProcessJpaRepository processes,
			PhoneSubmissionJpaRepository submissions, ReservationEventInboxJpaRepository inbox, PlatformTransactionManager transactions) {
		return new JpaPhoneSubmissionAdapter(processes, submissions, inbox, transactions);
	}

	@Bean
	public ReservationEventStore reservationEventStore(ReservationEventInboxJpaRepository inbox,
			ReservationProcessJpaRepository processes, PhoneSubmissionJpaRepository submissions, PlatformTransactionManager transactions) {
		return new JpaReservationEventAdapter(inbox, processes, submissions, transactions);
	}

	@Bean
	public ConfirmReservationProcessUseCase confirmReservationProcessUseCase(CurrentReservationUser currentUser,
			ReservationProcessStore processes, ReservationSlotValidator slots, AppointmentHoldConfirmer confirmer,
			ProcessReservationActionUseCase actions) {
		return new ConfirmReservationProcessUseCase(currentUser, processes, slots, confirmer, Clock.systemUTC(), actions);
	}

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
