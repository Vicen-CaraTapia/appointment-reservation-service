package ar.edu.um.prog2.appointmentreservation.reservation.infrastructure.persistence;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.SimpleTransactionStatus;

import ar.edu.um.prog2.appointmentreservation.reservation.application.exception.ReservationPersistenceException;
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
	private final JpaReservationProcessAdapter adapter = new JpaReservationProcessAdapter(users, processes, manager);

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

	private void preparePersistence() {
		when(manager.getTransaction(any())).thenReturn(transaction);
		var user = new UserEntity("alice", "$argon2id$v=19$m=19456,t=2,p=1$testSalt$testHash",
				null, null, "alice@example.test", null, "es", "alice");
		ReflectionTestUtils.setField(user, "id", 42L);
		when(users.getReferenceById(42L)).thenReturn(user);
	}

	private void assertStorageFailure() {
		assertThatThrownBy(() -> adapter.createHeld(OWNER, SELECTION, HOLD))
				.isInstanceOf(ReservationPersistenceException.class)
				.hasMessage("Reservation process could not be persisted; the remote hold may still exist").hasNoCause();
	}
}
