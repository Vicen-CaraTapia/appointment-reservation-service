package ar.edu.um.prog2.appointmentreservation.reservation.application.usecase;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import ar.edu.um.prog2.appointmentreservation.availability.application.service.AvailableAppointmentSlotsCalculator;
import ar.edu.um.prog2.appointmentreservation.professional.application.exception.ProfessionalLookupException;
import ar.edu.um.prog2.appointmentreservation.professional.application.model.CurrentProfessional;
import ar.edu.um.prog2.appointmentreservation.professional.application.port.out.CurrentProfessionalSource;
import ar.edu.um.prog2.appointmentreservation.professional.application.usecase.LoadEnabledProfessionalUseCase;
import ar.edu.um.prog2.appointmentreservation.reservation.application.exception.InitialConfirmationException;
import ar.edu.um.prog2.appointmentreservation.reservation.application.exception.InitialConfirmationException.Reason;
import ar.edu.um.prog2.appointmentreservation.reservation.application.exception.InvalidReservationSlotException;
import ar.edu.um.prog2.appointmentreservation.reservation.application.exception.ReservationAuthenticationRequiredException;
import ar.edu.um.prog2.appointmentreservation.reservation.application.exception.ReservationProcessStateException;
import ar.edu.um.prog2.appointmentreservation.reservation.application.model.AppointmentHold;
import ar.edu.um.prog2.appointmentreservation.reservation.application.model.AuthenticatedReservationUser;
import ar.edu.um.prog2.appointmentreservation.reservation.application.model.ConfirmationPatient;
import ar.edu.um.prog2.appointmentreservation.reservation.application.model.InitialReservationConfirmation;
import ar.edu.um.prog2.appointmentreservation.reservation.application.model.ReservationEventReceipt;
import ar.edu.um.prog2.appointmentreservation.reservation.application.model.ReservationEventReceipt.State;
import ar.edu.um.prog2.appointmentreservation.reservation.application.model.StartReservationCommand;
import ar.edu.um.prog2.appointmentreservation.reservation.application.model.StoredReservationProcess;
import ar.edu.um.prog2.appointmentreservation.reservation.application.port.out.AppointmentHoldConfirmer;
import ar.edu.um.prog2.appointmentreservation.reservation.application.port.out.CurrentReservationUser;
import ar.edu.um.prog2.appointmentreservation.reservation.application.port.out.ReservationProcessStore;
import ar.edu.um.prog2.appointmentreservation.reservation.application.service.ReservationSlotValidator;
import ar.edu.um.prog2.appointmentreservation.reservation.domain.model.ReservationProcessStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class ConfirmReservationProcessUseCaseTest {

	private static final AuthenticatedReservationUser OWNER = new AuthenticatedReservationUser(42, "renamed-login");
	private static final ConfirmationPatient PATIENT = new ConfirmationPatient("Ana", "Perez");
	private static final Instant NOW = Instant.parse("2026-11-19T12:00:00Z");
	private static final AppointmentHold HOLD = new AppointmentHold("hold/opaque", "process/opaque", NOW.plusSeconds(300));
	private static final StartReservationCommand SELECTION =
			new StartReservationCommand(202, LocalDate.of(2026, 11, 26), LocalTime.of(18, 0));
	private static final InitialReservationConfirmation ACCEPTED = new InitialReservationConfirmation(
			HOLD.reservationProcessId(), PATIENT.firstName(), PATIENT.lastName(), HOLD.expiresAt().plusNanos(789));
	private static final StoredReservationProcess HELD = observation(ReservationProcessStatus.HELD);
	private static final StoredReservationProcess CLAIM = observation(ReservationProcessStatus.CONFIRMATION_PENDING);
	private static final StoredReservationProcess WAITING = observation(ReservationProcessStatus.WAITING_FOR_PHONE);
	private final CurrentReservationUser currentUser = mock(CurrentReservationUser.class);
	private final ReservationProcessStore processes = mock(ReservationProcessStore.class);
	private final CurrentProfessionalSource professionals = mock(CurrentProfessionalSource.class);
	private final AppointmentHoldConfirmer confirmer = mock(AppointmentHoldConfirmer.class);
	private final ProcessReservationActionUseCase actions = mock(ProcessReservationActionUseCase.class);
	private final Clock clock = mock(Clock.class);
	private final ReservationSlotValidator slots = new ReservationSlotValidator(new LoadEnabledProfessionalUseCase(professionals),
			new AvailableAppointmentSlotsCalculator());
	private final ConfirmReservationProcessUseCase useCase =
			new ConfirmReservationProcessUseCase(currentUser, processes, slots, confirmer, clock, actions);

	@BeforeEach
	void noPendingEventsByDefault() {
		when(actions.resumePending(anyString(), anyInt())).thenReturn(List.of());
	}

	@Test
	void authenticatesValidatesClaimsThenConfirmsOnceAndRecordsTheAuthoritativeAcceptance() {
		prepareAccepted();
		when(processes.recordInitialConfirmation(123, 1, OWNER, ACCEPTED)).thenReturn(WAITING);
		when(processes.findOwned(123, OWNER)).thenReturn(Optional.of(HELD), Optional.of(CLAIM));
		assertThat(useCase.confirm(123, PATIENT)).isSameAs(WAITING);
		var order = inOrder(currentUser, processes, professionals, clock, confirmer, actions);
		order.verify(currentUser).requireAuthenticated();
		order.verify(processes).findOwned(123, OWNER);
		order.verify(clock).instant();
		order.verify(professionals).load(202);
		order.verify(clock).instant();
		order.verify(processes).claimInitialConfirmation(123, 0, OWNER, PATIENT, NOW);
		order.verify(clock).instant();
		order.verify(confirmer).confirm(HOLD, "user-42", PATIENT);
		order.verify(processes).recordInitialConfirmation(123, 1, OWNER, ACCEPTED);
		order.verify(actions).resumePending(HOLD.reservationProcessId(), 100);
		verifyNoMoreInteractions(currentUser, processes, professionals, clock, confirmer, actions);
	}

	@Test
	void anAbsentIdentityDoesNotReadPersistenceOrCallEitherExternalSystem() {
		var failure = new ReservationAuthenticationRequiredException();
		when(currentUser.requireAuthenticated()).thenThrow(failure);
		assertThatThrownBy(() -> useCase.confirm(123, PATIENT)).isSameAs(failure);
		verifyNoInteractions(processes, professionals, confirmer, clock, actions);
	}

	@Test
	void rejectsInvalidLocalInputBeforeAnyWork() {
		assertThatThrownBy(() -> useCase.confirm(0, PATIENT)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> useCase.confirm(-1, PATIENT)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> useCase.confirm(123, null)).isInstanceOf(NullPointerException.class);
		verifyNoInteractions(currentUser, processes, professionals, confirmer, clock);
	}

	@Test
	void absentOrForeignOwnedProcessesAreDeniedBeforeCatalogOrPost() {
		owned(Optional.empty());
		assertStateFailure(ReservationProcessStateException.Reason.NOT_FOUND);
		verifyNoInteractions(professionals, confirmer, clock);
		verify(processes, never()).claimInitialConfirmation(anyLong(), anyLong(), any(), any(), any());
	}

	@Test
	void pendingHoldOrMissingHoldDetailsNeverTriggerARemoteConfirmation() {
		for (var status : List.of(ReservationProcessStatus.PENDING_HOLD, ReservationProcessStatus.HELD)) {
			owned(Optional.of(new StoredReservationProcess(123, 0, status, SELECTION, "user-42", null, null, null)));
			assertStateFailure(ReservationProcessStateException.Reason.CONFLICT);
		}
		verifyNoInteractions(professionals, confirmer, clock);
	}

	@ParameterizedTest
	@ValueSource(longs = {0, 1})
	void alreadyExpiredHoldsNeverConsultCatalogOrClaim(long nanosAfterExpiry) {
		prepareHeld();
		when(clock.instant()).thenReturn(HOLD.expiresAt().plusNanos(nanosAfterExpiry));
		assertStateFailure(ReservationProcessStateException.Reason.EXPIRED);
		verifyNoInteractions(professionals, confirmer);
		verify(processes, never()).claimInitialConfirmation(anyLong(), anyLong(), any(), any(), any());
	}

	@Test
	void rechecksExpiryAfterCatalogBeforeClaiming() {
		prepareHeld();
		when(clock.instant()).thenReturn(NOW, HOLD.expiresAt());
		assertStateFailure(ReservationProcessStateException.Reason.EXPIRED);
		verify(professionals).load(202);
		verifyNoInteractions(confirmer);
		verify(processes, never()).claimInitialConfirmation(anyLong(), anyLong(), any(), any(), any());
	}

	@Test
	void aClaimThatConsumesTheDeadlineIsReleasedBeforeAnyPost() {
		prepareClaim();
		when(clock.instant()).thenReturn(NOW, NOW, HOLD.expiresAt());
		assertStateFailure(ReservationProcessStateException.Reason.EXPIRED);
		verify(processes).releaseInitialConfirmation(123, 1, OWNER);
		verifyNoInteractions(confirmer);
	}

	@ParameterizedTest
	@EnumSource(value = ReservationProcessStatus.class, names = {"CONFIRMATION_PENDING", "WAITING_FOR_PHONE"})
	void replaysTheObservedStateWithoutCatalogOrPostEvenWhenItsDeadlineHasPassed(ReservationProcessStatus status) {
		var observed = observation(status);
		owned(Optional.of(observed));
		assertThat(useCase.confirm(123, PATIENT)).isSameAs(observed);
		verifyNoInteractions(professionals, confirmer, clock);
		verify(processes).findOwned(123, OWNER);
		verify(actions).resumePending(HOLD.reservationProcessId(), 100);
		verifyNoMoreInteractions(processes, actions);
	}

	@ParameterizedTest
	@EnumSource(value = ReservationProcessStatus.class, names = {"CONFIRMATION_PENDING", "WAITING_FOR_PHONE"})
	void aRepeatCannotReplaceThePatientOfAnExistingAttempt(ReservationProcessStatus status) {
		owned(Optional.of(observation(status)));
		assertThatThrownBy(() -> useCase.confirm(123, new ConfirmationPatient("Other", "Patient")))
				.isInstanceOfSatisfying(ReservationProcessStateException.class,
						failure -> assertThat(failure.reason()).isEqualTo(ReservationProcessStateException.Reason.CONFLICT));
		verifyNoInteractions(professionals, confirmer, clock);
	}

	@ParameterizedTest
	@EnumSource(ProfessionalLookupException.Reason.class)
	void catalogFailureNeverClaimsOrConfirms(ProfessionalLookupException.Reason reason) {
		prepareHeld();
		var failure = new ProfessionalLookupException(reason);
		when(professionals.load(202)).thenThrow(failure);
		assertThatThrownBy(() -> useCase.confirm(123, PATIENT)).isSameAs(failure);
		verifyNoInteractions(confirmer);
		verify(processes, never()).claimInitialConfirmation(anyLong(), anyLong(), any(), any(), any());
	}

	@Test
	void aChangedCurrentScheduleRejectsTheSlotWithoutUsingOccupanciesOrClaiming() {
		prepareHeld();
		var professional = new CurrentProfessional(202, "Test", "Professional", true,
				new CurrentProfessional.Category(101, "Category", true), List.of());
		when(professionals.load(202)).thenReturn(professional);
		assertThatThrownBy(() -> useCase.confirm(123, PATIENT)).isInstanceOf(InvalidReservationSlotException.class);
		verifyNoInteractions(confirmer);
		verify(processes, never()).claimInitialConfirmation(anyLong(), anyLong(), any(), any(), any());
	}

	@ParameterizedTest
	@EnumSource(value = ReservationProcessStatus.class, names = {"CONFIRMATION_PENDING", "WAITING_FOR_PHONE"})
	void losingTheClaimRaceReadsTheWinningStateAndDoesNotPost(ReservationProcessStatus status) {
		prepareHeld();
		var winner = observation(status);
		when(processes.findOwned(123, OWNER)).thenReturn(Optional.of(HELD), Optional.of(winner));
		when(processes.claimInitialConfirmation(123, 0, OWNER, PATIENT, NOW))
				.thenThrow(new ReservationProcessStateException(ReservationProcessStateException.Reason.CONFLICT));
		assertThat(useCase.confirm(123, PATIENT)).isSameAs(winner);
		verify(processes, times(2)).findOwned(123, OWNER);
		verifyNoInteractions(confirmer);
	}

	@Test
	void aClaimConflictThatHasNoVerifiedProgressIsNotRetried() {
		prepareHeld();
		var failure = new ReservationProcessStateException(ReservationProcessStateException.Reason.CONFLICT);
		when(processes.claimInitialConfirmation(123, 0, OWNER, PATIENT, NOW)).thenThrow(failure);
		assertThatThrownBy(() -> useCase.confirm(123, PATIENT)).isSameAs(failure);
		verify(processes, times(2)).findOwned(123, OWNER);
		verify(processes).claimInitialConfirmation(123, 0, OWNER, PATIENT, NOW);
		verifyNoInteractions(confirmer);
	}

	@ParameterizedTest
	@EnumSource(value = ReservationProcessStateException.Reason.class, names = {"NOT_FOUND", "EXPIRED", "STORAGE_FAILURE"})
	void failureToObtainACommittedClaimNeverAuthorizesThePost(ReservationProcessStateException.Reason reason) {
		prepareHeld();
		var failure = new ReservationProcessStateException(reason);
		when(processes.claimInitialConfirmation(123, 0, OWNER, PATIENT, NOW)).thenThrow(failure);
		assertThatThrownBy(() -> useCase.confirm(123, PATIENT)).isSameAs(failure);
		verifyNoInteractions(confirmer);
	}

	@ParameterizedTest
	@EnumSource(value = Reason.class, names = {"PROFESSIONAL_NOT_FOUND", "INVALID_SLOT", "SLOT_ALREADY_RESERVED",
			"HOLD_NOT_FOUND", "HOLD_EXPIRED", "HOLD_OWNER_MISMATCH", "EXTERNAL_PATIENT_MISMATCH"})
	void onlyDefinitiveContractualRejectionsReleaseTheAttempt(Reason reason) {
		prepareClaim();
		var failure = new InitialConfirmationException(reason);
		when(confirmer.confirm(HOLD, "user-42", PATIENT)).thenThrow(failure);
		assertThatThrownBy(() -> useCase.confirm(123, PATIENT)).isSameAs(failure);
		assertThat(reason.definitiveRejection()).isTrue();
		verify(processes).releaseInitialConfirmation(123, 1, OWNER);
		verify(processes, never()).recordInitialConfirmation(anyLong(), anyLong(), any(), any());
		verify(confirmer).confirm(HOLD, "user-42", PATIENT);
		verifyNoMoreInteractions(confirmer);
	}

	@ParameterizedTest
	@EnumSource(value = Reason.class, names = {"RESERVATION_PROCESS_ALREADY_CONFIRMED", "TIMEOUT", "UNAVAILABLE",
			"UNAUTHORIZED", "FORBIDDEN", "REQUEST_REJECTED", "INVALID_RESPONSE", "LOCAL_STATE_FAILURE"})
	void uncertainOrUnclassifiedFailuresKeepTheClaimAndNeverRepeatThePost(Reason reason) {
		prepareClaim();
		var failure = new InitialConfirmationException(reason);
		when(confirmer.confirm(HOLD, "user-42", PATIENT)).thenThrow(failure);
		assertThatThrownBy(() -> useCase.confirm(123, PATIENT)).isSameAs(failure);
		assertThat(reason.definitiveRejection()).isFalse();
		verify(processes, never()).releaseInitialConfirmation(anyLong(), anyLong(), any());
		verify(processes, never()).recordInitialConfirmation(anyLong(), anyLong(), any(), any());
		verify(confirmer).confirm(HOLD, "user-42", PATIENT);
		verifyNoMoreInteractions(confirmer);
	}

	@Test
	void alreadyConfirmedRemotelyCanReturnOnlyAVerifiedLocalInitialAcceptance() {
		prepareClaim();
		when(confirmer.confirm(HOLD, "user-42", PATIENT))
				.thenThrow(new InitialConfirmationException(Reason.RESERVATION_PROCESS_ALREADY_CONFIRMED));
		when(processes.findOwned(123, OWNER)).thenReturn(Optional.of(HELD), Optional.of(WAITING));
		assertThat(useCase.confirm(123, PATIENT)).isSameAs(WAITING);
		verify(processes, times(2)).findOwned(123, OWNER);
		verify(processes, never()).releaseInitialConfirmation(anyLong(), anyLong(), any());
		verify(confirmer).confirm(HOLD, "user-42", PATIENT);
		verifyNoMoreInteractions(confirmer);
	}

	@Test
	void aKafkaEventThatAdvancedTheProcessBeforeTheRestResponseIsReturnedWithoutRegression() {
		prepareAccepted();
		var confirmed = observation(ReservationProcessStatus.CONFIRMED);
		when(processes.recordInitialConfirmation(123, 1, OWNER, ACCEPTED))
				.thenThrow(new ReservationProcessStateException(ReservationProcessStateException.Reason.CONFLICT));
		when(processes.findOwned(123, OWNER)).thenReturn(Optional.of(HELD), Optional.of(confirmed));
		assertThat(useCase.confirm(123, PATIENT)).isSameAs(confirmed);
		verify(actions).resumePending(HOLD.reservationProcessId(), 100);
		verify(confirmer).confirm(HOLD, "user-42", PATIENT);
		verifyNoMoreInteractions(confirmer);
	}

	@Test
	void acceptedRestResponseResumesPreviouslyDeferredDurableEvents() {
		prepareAccepted();
		var confirmed = observation(ReservationProcessStatus.CONFIRMED);
		when(processes.recordInitialConfirmation(123, 1, OWNER, ACCEPTED)).thenReturn(WAITING);
		when(processes.findOwned(123, OWNER)).thenReturn(Optional.of(HELD), Optional.of(confirmed));
		when(actions.resumePending(HOLD.reservationProcessId(), 100)).thenReturn(List.of(
				new ReservationEventReceipt("confirmation-1", State.APPLIED, 123L, false)));

		assertThat(useCase.confirm(123, PATIENT)).isSameAs(confirmed);

		verify(actions).resumePending(HOLD.reservationProcessId(), 100);
		verify(confirmer).confirm(HOLD, "user-42", PATIENT);
		verifyNoMoreInteractions(confirmer);
	}

	@ParameterizedTest
	@ValueSource(strings = {"null", "process", "first", "last"})
	void incompatibleApplicationResultsLeaveTheClaimPending(String invalid) {
		prepareClaim();
		var response = invalid.equals("null") ? null : new InitialReservationConfirmation(
				invalid.equals("process") ? "unrelated" : HOLD.reservationProcessId(),
				invalid.equals("first") ? "Other" : PATIENT.firstName(),
				invalid.equals("last") ? "Patient" : PATIENT.lastName(), ACCEPTED.expiresAt());
		when(confirmer.confirm(HOLD, "user-42", PATIENT)).thenReturn(response);
		assertInitialFailure(Reason.INVALID_RESPONSE);
		verify(processes, never()).recordInitialConfirmation(anyLong(), anyLong(), any(), any());
		verify(processes, never()).releaseInitialConfirmation(anyLong(), anyLong(), any());
	}

	@ParameterizedTest
	@EnumSource(ReservationProcessStateException.Reason.class)
	void aFailedLocalSaveAfterAcceptanceIsNotTreatedAsARemoteRollback(ReservationProcessStateException.Reason reason) {
		prepareAccepted();
		when(processes.recordInitialConfirmation(123, 1, OWNER, ACCEPTED))
				.thenThrow(new ReservationProcessStateException(reason));
		assertInitialFailure(Reason.LOCAL_STATE_FAILURE);
		verify(processes, times(2)).findOwned(123, OWNER);
		verify(processes, never()).releaseInitialConfirmation(anyLong(), anyLong(), any());
		verify(confirmer).confirm(HOLD, "user-42", PATIENT);
		verifyNoMoreInteractions(confirmer);
	}

	@Test
	void aSaveErrorFollowedByVerifiedLocalAcceptanceReturnsThatObservationWithoutAnotherPost() {
		prepareAccepted();
		when(processes.recordInitialConfirmation(123, 1, OWNER, ACCEPTED))
				.thenThrow(new ReservationProcessStateException(ReservationProcessStateException.Reason.STORAGE_FAILURE));
		when(processes.findOwned(123, OWNER)).thenReturn(Optional.of(HELD), Optional.of(WAITING));
		assertThat(useCase.confirm(123, PATIENT)).isSameAs(WAITING);
		verify(confirmer).confirm(HOLD, "user-42", PATIENT);
		verifyNoMoreInteractions(confirmer);
	}

	@Test
	void aFailedReadAfterRemoteAcceptanceRemainsAControlledLocalFailure() {
		prepareAccepted();
		when(processes.recordInitialConfirmation(123, 1, OWNER, ACCEPTED))
				.thenThrow(new ReservationProcessStateException(ReservationProcessStateException.Reason.STORAGE_FAILURE));
		when(processes.findOwned(123, OWNER)).thenReturn(Optional.of(HELD))
				.thenThrow(new ReservationProcessStateException(ReservationProcessStateException.Reason.STORAGE_FAILURE));
		assertInitialFailure(Reason.LOCAL_STATE_FAILURE);
		verify(confirmer).confirm(HOLD, "user-42", PATIENT);
		verifyNoMoreInteractions(confirmer);
	}

	@ParameterizedTest
	@ValueSource(strings = {"hold", "process", "patient-id", "patient-name", "missing-hold", "id"})
	void anUnrelatedLocalRowCannotDisguiseAFailedSaveAsSuccess(String mismatch) {
		prepareAccepted();
		var hold = mismatch.equals("missing-hold") ? null : new AppointmentHold(
				mismatch.equals("hold") ? "other-hold" : HOLD.holdId(),
				mismatch.equals("process") ? "other-process" : HOLD.reservationProcessId(), HOLD.expiresAt());
		var unrelated = new StoredReservationProcess(mismatch.equals("id") ? 124 : 123, 2,
				ReservationProcessStatus.WAITING_FOR_PHONE, SELECTION,
				mismatch.equals("patient-id") ? "user-99" : "user-42", hold,
				mismatch.equals("patient-name") ? "Other" : PATIENT.firstName(), PATIENT.lastName());
		when(processes.recordInitialConfirmation(123, 1, OWNER, ACCEPTED))
				.thenThrow(new ReservationProcessStateException(ReservationProcessStateException.Reason.CONFLICT));
		when(processes.findOwned(123, OWNER)).thenReturn(Optional.of(HELD), Optional.of(unrelated));
		assertInitialFailure(Reason.LOCAL_STATE_FAILURE);
	}

	@Test
	void aReleaseFailureNeverAuthorizesAnotherPostOrAFalseSuccessfulResult() {
		prepareClaim();
		when(confirmer.confirm(HOLD, "user-42", PATIENT)).thenThrow(new InitialConfirmationException(Reason.HOLD_EXPIRED));
		var failure = new ReservationProcessStateException(ReservationProcessStateException.Reason.STORAGE_FAILURE);
		when(processes.releaseInitialConfirmation(123, 1, OWNER)).thenThrow(failure);
		assertThatThrownBy(() -> useCase.confirm(123, PATIENT)).isSameAs(failure);
		verify(confirmer).confirm(HOLD, "user-42", PATIENT);
		verifyNoMoreInteractions(confirmer);
	}

	private void prepareHeld() {
		owned(Optional.of(HELD));
		when(clock.instant()).thenReturn(NOW);
		when(professionals.load(202)).thenReturn(new CurrentProfessional(202, "Test", "Professional", true,
				new CurrentProfessional.Category(101, "Category", true),
				List.of(new CurrentProfessional.WeeklySchedule(304, DayOfWeek.THURSDAY,
						LocalTime.of(18, 0), LocalTime.of(21, 0), 30, true))));
	}

	private void prepareClaim() {
		prepareHeld();
		when(processes.findOwned(123, OWNER)).thenReturn(Optional.of(HELD), Optional.of(CLAIM));
		when(processes.claimInitialConfirmation(123, 0, OWNER, PATIENT, NOW)).thenReturn(CLAIM);
	}

	private void prepareAccepted() {
		prepareClaim();
		when(confirmer.confirm(HOLD, "user-42", PATIENT)).thenReturn(ACCEPTED);
	}

	private void owned(Optional<StoredReservationProcess> value) {
		when(currentUser.requireAuthenticated()).thenReturn(OWNER);
		when(processes.findOwned(123, OWNER)).thenReturn(value);
	}

	private void assertStateFailure(ReservationProcessStateException.Reason reason) {
		assertThatThrownBy(() -> useCase.confirm(123, PATIENT)).isInstanceOfSatisfying(ReservationProcessStateException.class,
				failure -> assertThat(failure.reason()).isEqualTo(reason));
	}

	private void assertInitialFailure(Reason reason) {
		assertThatThrownBy(() -> useCase.confirm(123, PATIENT)).isInstanceOfSatisfying(InitialConfirmationException.class,
				failure -> assertThat(failure.reason()).isEqualTo(reason)).hasNoCause();
	}

	private static StoredReservationProcess observation(ReservationProcessStatus status) {
		boolean started = status != ReservationProcessStatus.HELD;
		return new StoredReservationProcess(123, status == ReservationProcessStatus.HELD ? 0 :
				status == ReservationProcessStatus.CONFIRMATION_PENDING ? 1 : 2, status, SELECTION, "user-42",
				status == ReservationProcessStatus.WAITING_FOR_PHONE ? new AppointmentHold(HOLD.holdId(), HOLD.reservationProcessId(),
						ACCEPTED.expiresAt().truncatedTo(ChronoUnit.MICROS)) : HOLD,
				started ? PATIENT.firstName() : null, started ? PATIENT.lastName() : null);
	}
}
