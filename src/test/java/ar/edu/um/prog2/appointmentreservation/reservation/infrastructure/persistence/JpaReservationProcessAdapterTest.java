package ar.edu.um.prog2.appointmentreservation.reservation.infrastructure.persistence;

import ar.edu.um.prog2.appointmentreservation.reservation.application.model.ReservationAssociation;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.SimpleTransactionStatus;

import ar.edu.um.prog2.appointmentreservation.reservation.application.exception.ReservationPersistenceException;
import ar.edu.um.prog2.appointmentreservation.reservation.application.exception.ReservationProcessStateException;
import ar.edu.um.prog2.appointmentreservation.reservation.application.exception.ReservationProcessStateException.Reason;
import ar.edu.um.prog2.appointmentreservation.reservation.application.model.InitialReservationConfirmation;
import ar.edu.um.prog2.appointmentreservation.reservation.application.model.ConfirmationPatient;
import ar.edu.um.prog2.appointmentreservation.reservation.application.model.AppointmentHold;
import ar.edu.um.prog2.appointmentreservation.reservation.application.model.AuthenticatedReservationUser;
import ar.edu.um.prog2.appointmentreservation.reservation.application.model.StartReservationCommand;
import ar.edu.um.prog2.appointmentreservation.reservation.domain.model.ReservationProcessStatus;
import ar.edu.um.prog2.appointmentreservation.reservation.infrastructure.persistence.entity.ReservationProcessEntity;
import ar.edu.um.prog2.appointmentreservation.reservation.infrastructure.persistence.repository.ReservationProcessJpaRepository;
import ar.edu.um.prog2.appointmentreservation.user.infrastructure.persistence.entity.UserEntity;
import ar.edu.um.prog2.appointmentreservation.user.infrastructure.persistence.repository.UserJpaRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class JpaReservationProcessAdapterTest {

	private static final AuthenticatedReservationUser OWNER = new AuthenticatedReservationUser(42, "alice");
	private static final StartReservationCommand SELECTION = new StartReservationCommand(Long.MAX_VALUE,
			LocalDate.of(2026, 11, 19), LocalTime.of(18, 0));
	private static final AppointmentHold HOLD = new AppointmentHold("hold-opaque", "process-opaque",
			Instant.parse("2026-11-17T22:28:18.514830573Z"));
	private final UserJpaRepository users = mock(UserJpaRepository.class);
	private final ReservationProcessJpaRepository processes = mock(ReservationProcessJpaRepository.class);
	private final PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
	private final SimpleTransactionStatus transaction = new SimpleTransactionStatus();
	private final JpaReservationProcessAdapter adapter = new JpaReservationProcessAdapter(users, processes, mock(ar.edu.um.prog2.appointmentreservation.reservation.infrastructure.persistence.repository.PhoneSubmissionJpaRepository.class), manager);

	@Test
	void savesACompleteHeldProcessAndReturnsOnlyAfterTheLocalCommit() {
		preparePersistence();
		when(processes.saveAndFlush(any())).thenAnswer(call -> {
			ReservationProcessEntity process = call.getArgument(0);
			assertThat(process.getOwner().getId()).isEqualTo(42);
			assertThat(process.getExternalPatientId()).isEqualTo("user-42");
			assertThat(process.getProfessionalId()).isEqualTo(Long.MAX_VALUE);
			assertThat(process.getAppointmentDate()).isEqualTo(SELECTION.date());
			assertThat(process.getStartTime()).isEqualTo(SELECTION.startTime());
			assertThat(process.getStatus()).isEqualTo(ReservationProcessStatus.HELD);
			assertThat(process.getHoldId()).isEqualTo(HOLD.holdId());
			assertThat(process.getExternalReservationProcessId()).isEqualTo(HOLD.reservationProcessId());
			assertThat(process.getExpiresAt()).isEqualTo(Instant.parse("2026-11-17T22:28:18.514830Z"))
					.isBeforeOrEqualTo(HOLD.expiresAt());
			assertThat(process.getCreatedBy()).isEqualTo("alice");
			assertThat(process.getLastModifiedBy()).isNull();
			ReflectionTestUtils.setField(process, "id", 123L);
			return process;
		});
		var result = adapter.createHeld(OWNER, SELECTION, HOLD);
		assertThat(result.id()).isEqualTo(123);
		assertThat(result.hold().holdId()).isEqualTo(HOLD.holdId());
		assertThat(result.hold().reservationProcessId()).isEqualTo(HOLD.reservationProcessId());
		assertThat(result.hold().expiresAt()).isEqualTo(Instant.parse("2026-11-17T22:28:18.514830Z"));
		var order = inOrder(manager, users, processes);
		order.verify(manager).getTransaction(any());
		order.verify(users).getReferenceById(42L);
		order.verify(processes).saveAndFlush(any());
		order.verify(manager).commit(transaction);
		verify(manager, never()).rollback(any());
	}

	@Test
	void rollsBackAndSanitizesFlushFailuresWithoutCallingThemRemoteSlotConflicts() {
		preparePersistence();
		when(processes.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("private database diagnostic"));
		assertStorageFailure();
		verify(manager).rollback(transaction);
		verify(manager, never()).commit(any());
	}

	@Test
	void sanitizesCommitFailureInsteadOfReturningAFalseSuccessfulResult() {
		preparePersistence();
		when(processes.saveAndFlush(any())).thenAnswer(call -> {
			ReservationProcessEntity process = call.getArgument(0);
			ReflectionTestUtils.setField(process, "id", 123L);
			return process;
		});
		doThrow(new TransactionSystemException("private commit diagnostic")).when(manager).commit(transaction);
		assertStorageFailure();
		verify(manager).commit(transaction);
	}

	@Test
	void sanitizesFailureToBeginTheLocalTransaction() {
		when(manager.getTransaction(any())).thenThrow(new TransactionSystemException("private connection diagnostic"));
		assertStorageFailure();
		verifyNoInteractions(users, processes);
	}

	@ParameterizedTest
	@ValueSource(ints = {0, 1, 2})
	void rejectsIncompleteApplicationInputsBeforeStartingPersistence(int missing) {
		assertThatThrownBy(() -> adapter.createHeld(missing == 0 ? null : OWNER,
				missing == 1 ? null : SELECTION, missing == 2 ? null : HOLD)).isInstanceOf(NullPointerException.class);
		verifyNoInteractions(manager, users, processes);
	}

	@Test
	void returnsADetachedOwnedObservationWithinAReadOnlyTransaction() {
		var entity = prepareOwnedHold();
		var result = adapter.findOwned(123, OWNER).orElseThrow();
		assertThat(result.id()).isEqualTo(123);
		assertThat(result.version()).isZero();
		assertThat(result.status()).isEqualTo(ReservationProcessStatus.HELD);
		assertThat(result.selection()).isEqualTo(SELECTION);
		assertThat(result.externalPatientId()).isEqualTo("user-42");
		assertThat(result.hold().reservationProcessId()).isEqualTo(HOLD.reservationProcessId());
		assertThat(result.patientFirstName()).isNull();
		assertThat(result.patientLastName()).isNull();
		verify(manager).getTransaction(argThat(definition -> definition.isReadOnly()));
		verify(manager).commit(transaction);
		assertThat(entity.getStatus()).isEqualTo(ReservationProcessStatus.HELD);
	}

	@Test
	void mapsPendingProcessesWithoutInventingAHold() {
		preparePersistence();
		var entity = new ReservationProcessEntity(users.getReferenceById(42L), SELECTION.professionalId(),
				SELECTION.date(), SELECTION.startTime(), "user-42", "alice");
		ReflectionTestUtils.setField(entity, "id", 123L);
		ReflectionTestUtils.setField(entity, "version", 0L);
		when(processes.findByIdAndOwner_Id(123, 42)).thenReturn(Optional.of(entity));
		var result = adapter.findOwned(123, OWNER).orElseThrow();
		assertThat(result.status()).isEqualTo(ReservationProcessStatus.PENDING_HOLD);
		assertThat(result.hold()).isNull();
	}

	@Test
	void missingOrForeignOwnedProcessesDoNotTriggerAnUnscopedLookup() {
		preparePersistence();
		when(processes.findByIdAndOwner_Id(123, 42)).thenReturn(Optional.empty());
		assertThat(adapter.findOwned(123, OWNER)).isEmpty();
		verify(processes, never()).findById(any());
		verify(processes, never()).flush();
	}

	@Test
	void recordsConfirmationAndReturnsOnlyAfterCommit() {
		prepareClaimedHold();
		var result = adapter.recordInitialConfirmation(123, 0, OWNER, confirmation());
		assertThat(result.status()).isEqualTo(ReservationProcessStatus.WAITING_FOR_PHONE);
		assertThat(result.patientFirstName()).isEqualTo("Ana");
		assertThat(result.patientLastName()).isEqualTo("Perez");
		assertThat(result.hold().expiresAt()).isEqualTo(Instant.parse("2026-11-17T22:28:18.514830Z"));
		var order = inOrder(manager, processes);
		order.verify(manager).getTransaction(any());
		order.verify(processes).findByIdAndOwner_Id(123, 42);
		order.verify(processes).flush();
		order.verify(manager).commit(transaction);
	}

	@Test
	void writeOfAbsentOrForeignOwnedIdHasTheSameSafeNotFoundOutcome() {
		preparePersistence();
		when(processes.findByIdAndOwner_Id(123, 42)).thenReturn(Optional.empty());
		assertStateFailure(Reason.NOT_FOUND);
		verify(processes, never()).findById(any());
		verify(processes, never()).flush();
		verify(manager).rollback(transaction);
	}

	@ParameterizedTest
	@ValueSource(strings = {"version", "status", "external-id"})
	void refusesStaleOrIncompatibleObservationsBeforeChangingTheEntity(String mismatch) {
		var entity = prepareClaimedHold();
		if (mismatch.equals("version")) {
			ReflectionTestUtils.setField(entity, "version", 1L);
		} else if (mismatch.equals("status")) {
			ReflectionTestUtils.setField(entity, "status", ReservationProcessStatus.PENDING_HOLD);
		} else {
			ReflectionTestUtils.setField(entity, "externalReservationProcessId", "other-process");
		}
		assertStateFailure(Reason.CONFLICT);
		assertThat(entity.getPatientFirstName()).isEqualTo("Ana");
		verify(processes, never()).flush();
	}

	@Test
	void translatesOptimisticConflictWithoutLeakingItsDiagnostics() {
		prepareClaimedHold();
		doThrow(new OptimisticLockingFailureException("private diagnostic")).when(processes).flush();
		assertStateFailure(Reason.CONFLICT);
		verify(manager).rollback(transaction);
	}

	@ParameterizedTest
	@ValueSource(strings = {"begin", "read", "flush", "commit"})
	void sanitizesStorageFailureAtEveryWriteTransactionStage(String stage) {
		prepareClaimedHold();
		var failure = new TransactionSystemException("private diagnostic with patient input and credentials");
		switch (stage) {
			case "begin" -> when(manager.getTransaction(any())).thenThrow(failure);
			case "read" -> when(processes.findByIdAndOwner_Id(123, 42)).thenThrow(failure);
			case "flush" -> doThrow(failure).when(processes).flush();
			case "commit" -> doThrow(failure).when(manager).commit(transaction);
			default -> throw new IllegalArgumentException("Unexpected test stage");
		}
		assertStateFailure(Reason.STORAGE_FAILURE);
	}

	@Test
	void readFailureDoesNotLookLikeAnEmptyOwnedResultOrExposeItsCause() {
		prepareOwnedHold();
		when(processes.findByIdAndOwner_Id(123, 42)).thenThrow(new TransactionSystemException("private diagnostic"));
		assertThatThrownBy(() -> adapter.findOwned(123, OWNER))
				.isInstanceOfSatisfying(ReservationProcessStateException.class,
						failure -> assertThat(failure.reason()).isEqualTo(Reason.STORAGE_FAILURE))
				.hasNoCause().hasMessageNotContaining("private diagnostic");
	}

	@Test
	void rejectsInvalidWriteIdentityAndMissingConfirmationBeforeAccessingStorage() {
		assertThatThrownBy(() -> adapter.findOwned(0, OWNER)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> adapter.findOwned(123, null)).isInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> adapter.recordInitialConfirmation(123, -1, OWNER, confirmation()))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> adapter.recordInitialConfirmation(123, 0, OWNER, null))
				.isInstanceOf(NullPointerException.class);
		verifyNoInteractions(manager, users, processes);
	}

	@Test
	void commitsTheClaimBeforeReturningItsObservationAndDoesNotExtendExpiration() {
		prepareOwnedHold();
		var result = adapter.claimInitialConfirmation(123, 0, OWNER, new ConfirmationPatient("Ana", "Perez"), HOLD.expiresAt().minusSeconds(1));
		assertThat(result.status()).isEqualTo(ReservationProcessStatus.CONFIRMATION_PENDING);
		assertThat(result.patientFirstName()).isEqualTo("Ana");
		assertThat(result.patientLastName()).isEqualTo("Perez");
		assertThat(result.hold().expiresAt()).isEqualTo(HOLD.expiresAt());
		var order = inOrder(manager, processes);
		order.verify(manager).getTransaction(any());
		order.verify(processes).findByIdAndOwner_Id(123, 42);
		order.verify(processes).flush();
		order.verify(manager).commit(transaction);
	}

	@ParameterizedTest
	@ValueSource(longs = {0, 1})
	void rejectsClaimAtOrAfterTheObservedDeadlineWithoutMutation(long nanosAfterDeadline) {
		var entity = prepareOwnedHold();
		assertThatThrownBy(() -> adapter.claimInitialConfirmation(123, 0, OWNER,
				new ConfirmationPatient("Ana", "Perez"), HOLD.expiresAt().plusNanos(nanosAfterDeadline)))
				.isInstanceOfSatisfying(ReservationProcessStateException.class,
						failure -> assertThat(failure.reason()).isEqualTo(Reason.EXPIRED));
		assertThat(entity.getStatus()).isEqualTo(ReservationProcessStatus.HELD);
		assertThat(entity.getPatientFirstName()).isNull();
		verify(processes, never()).flush();
	}

	@Test
	void refusesASecondClaimAndProtectsTheOriginalPatient() {
		var entity = prepareClaimedHold();
		assertThatThrownBy(() -> adapter.claimInitialConfirmation(123, 0, OWNER,
				new ConfirmationPatient("Other", "Patient"), HOLD.expiresAt().minusSeconds(1)))
				.isInstanceOfSatisfying(ReservationProcessStateException.class,
						failure -> assertThat(failure.reason()).isEqualTo(Reason.CONFLICT));
		assertThat(entity.getPatientFirstName()).isEqualTo("Ana");
		verify(processes, never()).flush();
	}

	@Test
	void doesNotReturnAPostPermitIfClaimCommitFails() {
		prepareOwnedHold();
		doThrow(new TransactionSystemException("private diagnostic")).when(manager).commit(transaction);
		assertThatThrownBy(() -> adapter.claimInitialConfirmation(123, 0, OWNER,
				new ConfirmationPatient("Ana", "Perez"), HOLD.expiresAt().minusSeconds(1)))
				.isInstanceOfSatisfying(ReservationProcessStateException.class,
						failure -> assertThat(failure.reason()).isEqualTo(Reason.STORAGE_FAILURE))
				.hasNoCause().hasMessageNotContaining("private diagnostic");
	}

	@Test
	void releasesOnlyAClaimedVersionAndClearsPatientDetails() {
		prepareClaimedHold();
		var result = adapter.releaseInitialConfirmation(123, 0, OWNER);
		assertThat(result.status()).isEqualTo(ReservationProcessStatus.HELD);
		assertThat(result.patientFirstName()).isNull();
		assertThat(result.patientLastName()).isNull();
		assertThat(result.hold().expiresAt()).isEqualTo(HOLD.expiresAt());
		verify(manager).commit(transaction);
	}

	@Test
	void refusesReleaseOfAnUnclaimedProcessAndAcceptanceForDifferentPatientNames() {
		var entity = prepareOwnedHold();
		assertThatThrownBy(() -> adapter.releaseInitialConfirmation(123, 0, OWNER))
				.isInstanceOfSatisfying(ReservationProcessStateException.class,
						failure -> assertThat(failure.reason()).isEqualTo(Reason.CONFLICT));
		entity.claimConfirmation("Different", "Patient", "alice");
		assertStateFailure(Reason.CONFLICT);
		assertThat(entity.getPatientFirstName()).isEqualTo("Different");
		verify(processes, never()).flush();
	}

	private ReservationProcessEntity prepareClaimedHold() {
		var entity = prepareOwnedHold();
		entity.claimConfirmation("Ana", "Perez", "alice");
		return entity;
	}

	private ReservationProcessEntity prepareOwnedHold() {
		preparePersistence();
		var entity = new ReservationProcessEntity(users.getReferenceById(42L), SELECTION.professionalId(),
				SELECTION.date(), SELECTION.startTime(), "user-42", "alice");
		entity.markHeld(HOLD.holdId(), HOLD.reservationProcessId(), HOLD.expiresAt(), "alice");
		ReflectionTestUtils.setField(entity, "id", 123L);
		ReflectionTestUtils.setField(entity, "version", 0L);
		when(processes.findByIdAndOwner_Id(123, 42)).thenReturn(Optional.of(entity));
		return entity;
	}

	private static InitialReservationConfirmation confirmation() {
		return new InitialReservationConfirmation(HOLD.reservationProcessId(), "Ana", "Perez", HOLD.expiresAt());
	}

	private void assertStateFailure(Reason reason) {
		assertThatThrownBy(() -> adapter.recordInitialConfirmation(123, 0, OWNER, confirmation()))
				.isInstanceOfSatisfying(ReservationProcessStateException.class,
						failure -> assertThat(failure.reason()).isEqualTo(reason))
				.hasNoCause().hasMessageNotContaining("private diagnostic");
	}

	@Test
	void readsAssociationSnapshotsInAReadOnlyTransactionWithoutSavingEntities() {
		preparePersistence();
		var processIds = java.util.Set.of("process-opaque");
		var reservationIds = java.util.Set.of(44L);
		var association = new ReservationAssociation(
				123,42,"user-42","process-opaque",44L,SELECTION.professionalId(),SELECTION.date(),SELECTION.startTime());
		when(processes.findReservationAssociations(processIds,reservationIds)).thenReturn(java.util.List.of(association));
		assertThat(adapter.findReservationAssociations(processIds,reservationIds)).containsExactly(association);
		verify(manager).getTransaction(argThat(definition -> definition.isReadOnly()));
		verify(manager).commit(transaction);
		verify(processes,never()).saveAndFlush(any());
	}

	@Test
	void associationLookupSanitizesDatabaseErrorsRatherThanReturningEmptyResults() {
		preparePersistence();
		when(processes.findReservationAssociations(any(),any())).thenThrow(new DataIntegrityViolationException("private SQL diagnostic"));
		assertThatThrownBy(() -> adapter.findReservationAssociations(java.util.Set.of("process-opaque"),java.util.Set.of(44L)))
				.isInstanceOfSatisfying(ReservationProcessStateException.class, error -> assertThat(error.reason()).isEqualTo(Reason.STORAGE_FAILURE))
				.hasNoCause().hasMessageNotContaining("private SQL diagnostic");
		verify(manager).rollback(transaction);
	}

	@Test
	void emptyOrOversizedAssociationQueriesDoNotReachTheDatabase() {
		assertThat(adapter.findReservationAssociations(java.util.Set.of(),java.util.Set.of())).isEmpty();
		var ids = java.util.stream.IntStream.range(0,1001).mapToObj(Integer::toString).collect(java.util.stream.Collectors.toSet());
		assertThatThrownBy(() -> adapter.findReservationAssociations(ids,java.util.Set.of())).isInstanceOf(IllegalArgumentException.class);
		verifyNoInteractions(processes,manager);
	}

	private void preparePersistence() {
		when(manager.getTransaction(any())).thenReturn(transaction);
		var user = new UserEntity("alice", "$argon2id$v=19$m=19456,t=2,p=1$testSalt$testHash",
				null, null, "alice@example.test", null, "es", "alice");
		ReflectionTestUtils.setField(user, "id", 42L);
		when(users.getReferenceById(42L)).thenReturn(user);
	}

	@Test
	void claimsCancellationAfterCheckingOwnershipAndCommitsBeforeReturning() {
		preparePersistence();
		var process = new ReservationProcessEntity(users.getReferenceById(42L), 202,
				SELECTION.date(), SELECTION.startTime(), "user-42", "alice");
		process.markHeld("hold", "trusted-process", HOLD.expiresAt(), "alice");
		ReflectionTestUtils.setField(process, "status", ReservationProcessStatus.CONFIRMED);
		ReflectionTestUtils.setField(process, "externalReservationId", 8251L);
		when(processes.findOwnedForUpdate(123, 42)).thenReturn(Optional.of(process));
		var now = Instant.now();
		var first = adapter.prepareCancellation(123, OWNER, "private reason", now);
		assertThat(first.newlyClaimed()).isTrue();
		assertThat(first.externalProcessId()).isEqualTo("trusted-process");
		assertThat(first.status()).isEqualTo(ReservationProcessStatus.CANCELLATION_PENDING);
		assertThat(first.toString()).doesNotContain("private reason");
		var replay = adapter.prepareCancellation(123, OWNER, "changed reason", now.plusSeconds(1));
		assertThat(replay.newlyClaimed()).isFalse();
		assertThat(replay.reason()).isEqualTo("private reason");
		assertThat(replay.requestedAt()).isEqualTo(now);
		verify(processes).flush();
	}

	@Test
	void foreignOrMissingCancellationCannotCreateAnIntent() {
		preparePersistence();
		when(processes.findOwnedForUpdate(123, 42)).thenReturn(Optional.empty());
		assertThatThrownBy(() -> adapter.prepareCancellation(123, OWNER, null, Instant.now()))
				.isInstanceOfSatisfying(ReservationProcessStateException.class,
						error -> assertThat(error.reason()).isEqualTo(Reason.NOT_FOUND));
		verify(processes, never()).flush();
		verify(manager).rollback(transaction);
	}

	@ParameterizedTest
	@org.junit.jupiter.params.provider.EnumSource(value = ReservationProcessStatus.class,
			names = {"CONFIRMED", "CANCELLED", "CANCELLATION_PENDING"},
			mode = org.junit.jupiter.params.provider.EnumSource.Mode.EXCLUDE)
	void noncancelableStatesDoNotCreateAnIntent(ReservationProcessStatus status) {
		preparePersistence();
		var process = new ReservationProcessEntity(users.getReferenceById(42L), 202,
				SELECTION.date(), SELECTION.startTime(), "user-42", "alice");
		ReflectionTestUtils.setField(process, "status", status);
		ReflectionTestUtils.setField(process, "externalReservationId", 8251L);
		when(processes.findOwnedForUpdate(123, 42)).thenReturn(Optional.of(process));
		assertThatThrownBy(() -> adapter.prepareCancellation(123, OWNER, null, Instant.now()))
				.isInstanceOfSatisfying(ReservationProcessStateException.class,
						error -> assertThat(error.reason()).isEqualTo(Reason.CONFLICT));
		assertThat(process.getCancellationRequestedAt()).isNull();
		verify(processes, never()).flush();
	}

	@Test
	void failedCancellationClaimCommitIsNotReturnedAsSuccess() {
		preparePersistence();
		var process = new ReservationProcessEntity(users.getReferenceById(42L), 202,
				SELECTION.date(), SELECTION.startTime(), "user-42", "alice");
		process.markHeld("hold", "trusted-process", HOLD.expiresAt(), "alice");
		ReflectionTestUtils.setField(process, "status", ReservationProcessStatus.CONFIRMED);
		ReflectionTestUtils.setField(process, "externalReservationId", 8251L);
		when(processes.findOwnedForUpdate(123, 42)).thenReturn(Optional.of(process));
		doThrow(new TransactionSystemException("private diagnostic")).when(manager).commit(transaction);
		assertThatThrownBy(() -> adapter.prepareCancellation(123, OWNER, null, Instant.now()))
				.isInstanceOfSatisfying(ReservationProcessStateException.class,
						error -> assertThat(error.reason()).isEqualTo(Reason.STORAGE_FAILURE)).hasNoCause();
	}

	private void assertStorageFailure() {
		assertThatThrownBy(() -> adapter.createHeld(OWNER, SELECTION, HOLD))
				.isInstanceOf(ReservationPersistenceException.class)
				.hasMessage("Reservation process could not be persisted; the remote hold may still exist").hasNoCause();
	}
}
