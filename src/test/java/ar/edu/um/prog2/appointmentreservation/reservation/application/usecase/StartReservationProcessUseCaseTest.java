package ar.edu.um.prog2.appointmentreservation.reservation.application.usecase;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import ar.edu.um.prog2.appointmentreservation.availability.application.service.AvailableAppointmentSlotsCalculator;
import ar.edu.um.prog2.appointmentreservation.professional.application.exception.ProfessionalLookupException;
import ar.edu.um.prog2.appointmentreservation.professional.application.model.CurrentProfessional;
import ar.edu.um.prog2.appointmentreservation.professional.application.port.out.CurrentProfessionalSource;
import ar.edu.um.prog2.appointmentreservation.professional.application.usecase.LoadEnabledProfessionalUseCase;
import ar.edu.um.prog2.appointmentreservation.reservation.application.exception.HoldRequestException;
import ar.edu.um.prog2.appointmentreservation.reservation.application.exception.InvalidReservationSlotException;
import ar.edu.um.prog2.appointmentreservation.reservation.application.exception.ReservationAuthenticationRequiredException;
import ar.edu.um.prog2.appointmentreservation.reservation.application.exception.ReservationPersistenceException;
import ar.edu.um.prog2.appointmentreservation.reservation.application.model.AppointmentHold;
import ar.edu.um.prog2.appointmentreservation.reservation.application.model.AuthenticatedReservationUser;
import ar.edu.um.prog2.appointmentreservation.reservation.application.model.HeldReservationProcess;
import ar.edu.um.prog2.appointmentreservation.reservation.application.model.StartReservationCommand;
import ar.edu.um.prog2.appointmentreservation.reservation.application.port.out.AppointmentHoldRequester;
import ar.edu.um.prog2.appointmentreservation.reservation.application.port.out.CurrentReservationUser;
import ar.edu.um.prog2.appointmentreservation.reservation.application.port.out.ReservationProcessStore;
import ar.edu.um.prog2.appointmentreservation.reservation.application.service.ReservationSlotValidator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StartReservationProcessUseCaseTest {

	private static final AuthenticatedReservationUser OWNER = new AuthenticatedReservationUser(42, "alice");
	private static final StartReservationCommand SELECTION = new StartReservationCommand(202,
			LocalDate.of(2026, 11, 19), LocalTime.of(18, 0));
	private static final AppointmentHold HOLD = new AppointmentHold("hold-opaque", "process-opaque",
			Instant.parse("2026-11-17T22:28:18.514830573Z"));
	@Mock private CurrentReservationUser currentUser;
	@Mock private CurrentProfessionalSource professionals;
	@Mock private AppointmentHoldRequester holds;
	@Mock private ReservationProcessStore processes;
	private StartReservationProcessUseCase useCase;

	@BeforeEach
	void usesTheRealCurrentProfessionalAndSlotValidation() {
		var validator = new ReservationSlotValidator(new LoadEnabledProfessionalUseCase(professionals),
				new AvailableAppointmentSlotsCalculator());
		useCase = new StartReservationProcessUseCase(currentUser, validator, holds, processes);
	}

	@Test
	void authenticatesValidatesThenRequestsOneHoldAndSavesItForTheTrustedOwner() {
		when(currentUser.requireAuthenticated()).thenReturn(OWNER);
		when(professionals.load(202)).thenReturn(professional(true));
		when(holds.createHold(SELECTION, "user-42")).thenReturn(HOLD);
		var persisted = new HeldReservationProcess(123, HOLD);
		when(processes.createHeld(OWNER, SELECTION, HOLD)).thenReturn(persisted);

		assertThat(useCase.start(SELECTION)).isSameAs(persisted);

		var order = inOrder(currentUser, professionals, holds, processes);
		order.verify(currentUser).requireAuthenticated();
		order.verify(professionals).load(202);
		order.verify(holds).createHold(SELECTION, "user-42");
		order.verify(processes).createHeld(OWNER, SELECTION, HOLD);
		verifyNoMoreInteractions(currentUser, professionals, holds, processes);
	}

	@Test
	void rejectsMissingSelectionWithoutAnyExternalWork() {
		assertThatThrownBy(() -> useCase.start(null)).isInstanceOf(NullPointerException.class);
		verifyNoInteractions(currentUser, professionals, holds, processes);
	}

	@Test
	void unauthenticatedCallsCannotConsultCatalogCreateHoldsOrWriteProcesses() {
		var failure = new ReservationAuthenticationRequiredException();
		when(currentUser.requireAuthenticated()).thenThrow(failure);
		assertThatThrownBy(() -> useCase.start(SELECTION)).isSameAs(failure);
		verifyNoInteractions(professionals, holds, processes);
	}

	@ParameterizedTest
	@EnumSource(ProfessionalLookupException.Reason.class)
	void catalogFailuresStopTheWorkflowBeforeAnyHoldOrLocalWrite(ProfessionalLookupException.Reason reason) {
		when(currentUser.requireAuthenticated()).thenReturn(OWNER);
		var failure = new ProfessionalLookupException(reason);
		when(professionals.load(202)).thenThrow(failure);
		assertThatThrownBy(() -> useCase.start(SELECTION)).isSameAs(failure);
		verifyNoInteractions(holds, processes);
	}

	@Test
	void aCurrentlyDisabledProfessionalCannotCreateAHold() {
		when(currentUser.requireAuthenticated()).thenReturn(OWNER);
		when(professionals.load(202)).thenReturn(professional(false));
		assertThatThrownBy(() -> useCase.start(SELECTION)).isInstanceOfSatisfying(ProfessionalLookupException.class,
				failure -> assertThat(failure.reason()).isEqualTo(ProfessionalLookupException.Reason.DISABLED));
		verifyNoInteractions(holds, processes);
	}

	@ParameterizedTest
	@ValueSource(strings = {"17:30", "18:01", "18:15", "21:00"})
	void anInvalidSelectedSlotCannotCreateAHoldOrAPendingRow(String time) {
		when(currentUser.requireAuthenticated()).thenReturn(OWNER);
		when(professionals.load(202)).thenReturn(professional(true));
		var invalid = new StartReservationCommand(202, SELECTION.date(), LocalTime.parse(time));
		assertThatThrownBy(() -> useCase.start(invalid)).isInstanceOf(InvalidReservationSlotException.class);
		verifyNoInteractions(holds, processes);
	}

	@ParameterizedTest
	@EnumSource(HoldRequestException.Reason.class)
	void remoteRejectionsAndAmbiguousErrorsArePropagatedWithoutRetryOrInventedLocalState(HoldRequestException.Reason reason) {
		when(currentUser.requireAuthenticated()).thenReturn(OWNER);
		when(professionals.load(202)).thenReturn(professional(true));
		var failure = new HoldRequestException(reason);
		when(holds.createHold(SELECTION, "user-42")).thenThrow(failure);
		assertThatThrownBy(() -> useCase.start(SELECTION)).isSameAs(failure);
		verify(holds).createHold(SELECTION, "user-42");
		verifyNoMoreInteractions(holds);
		verifyNoInteractions(processes);
	}

	@Test
	void failureToSaveAnAlreadySuccessfulHoldDoesNotRepeatTheRemoteOrLocalOperation() {
		when(currentUser.requireAuthenticated()).thenReturn(OWNER);
		when(professionals.load(202)).thenReturn(professional(true));
		when(holds.createHold(SELECTION, "user-42")).thenReturn(HOLD);
		var failure = new ReservationPersistenceException();
		when(processes.createHeld(OWNER, SELECTION, HOLD)).thenThrow(failure);
		assertThatThrownBy(() -> useCase.start(SELECTION)).isSameAs(failure);
		verify(holds).createHold(SELECTION, "user-42");
		verify(processes).createHeld(OWNER, SELECTION, HOLD);
		verifyNoMoreInteractions(holds, processes);
	}

	@Test
	void eachCallConsultsCatalogAgainInsteadOfReusingPreviouslyAcceptedScheduleData() {
		when(currentUser.requireAuthenticated()).thenReturn(OWNER);
		when(professionals.load(202)).thenReturn(professional(true), professional(false));
		when(holds.createHold(SELECTION, "user-42")).thenReturn(HOLD);
		when(processes.createHeld(OWNER, SELECTION, HOLD)).thenReturn(new HeldReservationProcess(123, HOLD));
		assertThat(useCase.start(SELECTION).id()).isEqualTo(123);
		assertThatThrownBy(() -> useCase.start(SELECTION)).isInstanceOf(ProfessionalLookupException.class);
		verify(professionals, times(2)).load(202);
		verify(holds).createHold(SELECTION, "user-42");
		verify(processes).createHeld(OWNER, SELECTION, HOLD);
		verifyNoMoreInteractions(holds, processes);
	}

	@Test
	void patientIdentityFollowsTheAuthenticatedUserOnEachCall() {
		var bob = new AuthenticatedReservationUser(43, "bob");
		var nextSelection = new StartReservationCommand(202, SELECTION.date(), LocalTime.of(18, 30));
		var nextHold = new AppointmentHold("hold-bob", "process-bob", HOLD.expiresAt());
		when(currentUser.requireAuthenticated()).thenReturn(OWNER, bob);
		when(professionals.load(202)).thenReturn(professional(true));
		when(holds.createHold(SELECTION, "user-42")).thenReturn(HOLD);
		when(holds.createHold(nextSelection, "user-43")).thenReturn(nextHold);
		when(processes.createHeld(OWNER, SELECTION, HOLD)).thenReturn(new HeldReservationProcess(123, HOLD));
		when(processes.createHeld(bob, nextSelection, nextHold)).thenReturn(new HeldReservationProcess(124, nextHold));
		assertThat(useCase.start(SELECTION).id()).isEqualTo(123);
		assertThat(useCase.start(nextSelection).id()).isEqualTo(124);
		verify(holds).createHold(SELECTION, "user-42");
		verify(holds).createHold(nextSelection, "user-43");
		verify(processes).createHeld(OWNER, SELECTION, HOLD);
		verify(processes).createHeld(bob, nextSelection, nextHold);
	}

	private static CurrentProfessional professional(boolean enabled) {
		return new CurrentProfessional(202, "Martin", "Rivas", enabled,
				new CurrentProfessional.Category(101, "Cardiologia", true),
				List.of(new CurrentProfessional.WeeklySchedule(304, DayOfWeek.THURSDAY,
						LocalTime.of(18, 0), LocalTime.of(21, 0), 30, true)));
	}
}
