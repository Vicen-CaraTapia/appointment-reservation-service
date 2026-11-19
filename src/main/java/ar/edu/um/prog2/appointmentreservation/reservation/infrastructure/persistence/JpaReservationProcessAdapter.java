package ar.edu.um.prog2.appointmentreservation.reservation.infrastructure.persistence;

import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Optional;

import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import ar.edu.um.prog2.appointmentreservation.reservation.application.ExternalPatientId;
import ar.edu.um.prog2.appointmentreservation.reservation.application.exception.ReservationPersistenceException;
import ar.edu.um.prog2.appointmentreservation.reservation.application.exception.ReservationProcessStateException;
import ar.edu.um.prog2.appointmentreservation.reservation.application.exception.ReservationProcessStateException.Reason;
import ar.edu.um.prog2.appointmentreservation.reservation.application.model.AppointmentHold;
import ar.edu.um.prog2.appointmentreservation.reservation.application.model.AuthenticatedReservationUser;
import ar.edu.um.prog2.appointmentreservation.reservation.application.model.HeldReservationProcess;
import ar.edu.um.prog2.appointmentreservation.reservation.application.model.StartReservationCommand;
import ar.edu.um.prog2.appointmentreservation.reservation.application.model.StoredReservationProcess;
import ar.edu.um.prog2.appointmentreservation.reservation.application.model.InitialReservationConfirmation;
import ar.edu.um.prog2.appointmentreservation.reservation.domain.model.ReservationProcessStatus;
import ar.edu.um.prog2.appointmentreservation.reservation.application.port.out.ReservationProcessStore;
import ar.edu.um.prog2.appointmentreservation.reservation.infrastructure.persistence.entity.ReservationProcessEntity;
import ar.edu.um.prog2.appointmentreservation.reservation.infrastructure.persistence.repository.ReservationProcessJpaRepository;
import ar.edu.um.prog2.appointmentreservation.user.infrastructure.persistence.repository.UserJpaRepository;

public final class JpaReservationProcessAdapter implements ReservationProcessStore {

	private final UserJpaRepository users;
	private final ReservationProcessJpaRepository processes;
	private final TransactionTemplate transactions;
	private final TransactionTemplate reads;

	public JpaReservationProcessAdapter(UserJpaRepository users, ReservationProcessJpaRepository processes,
			PlatformTransactionManager transactionManager) {
		this.users = Objects.requireNonNull(users);
		this.processes = Objects.requireNonNull(processes);
		this.transactions = new TransactionTemplate(Objects.requireNonNull(transactionManager));
		this.reads = new TransactionTemplate(transactionManager);
		this.reads.setReadOnly(true);
	}

	@Override
	public Optional<StoredReservationProcess> findOwned(long id, AuthenticatedReservationUser owner) {
		requireIdentity(id, owner);
		try {
			return reads.execute(status -> processes.findByIdAndOwner_Id(id, owner.userId())
					.map(JpaReservationProcessAdapter::observation));
		} catch (RuntimeException failure) {
			throw new ReservationProcessStateException(Reason.STORAGE_FAILURE);
		}
	}

	@Override
	public StoredReservationProcess recordInitialConfirmation(long id, long expectedVersion,
			AuthenticatedReservationUser owner, InitialReservationConfirmation confirmation) {
		requireIdentity(id, owner);
		Objects.requireNonNull(confirmation, "Initial acceptance is required");
		if (expectedVersion < 0) {
			throw new IllegalArgumentException("A nonnegative observation version is required");
		}
		try {
			return transactions.execute(status -> {
				var process = processes.findByIdAndOwner_Id(id, owner.userId())
						.orElseThrow(() -> new ReservationProcessStateException(Reason.NOT_FOUND));
				if (process.getVersion() != expectedVersion
						|| process.getStatus() != ReservationProcessStatus.HELD
						|| !confirmation.reservationProcessId().equals(process.getExternalReservationProcessId())) {
					throw new ReservationProcessStateException(Reason.CONFLICT);
				}
				process.markWaitingForPhone(confirmation.patientFirstName(), confirmation.patientLastName(),
						confirmation.expiresAt(), owner.login());
				processes.flush();
				return observation(process);
			});
		} catch (ReservationProcessStateException failure) {
			throw failure;
		} catch (OptimisticLockingFailureException failure) {
			throw new ReservationProcessStateException(Reason.CONFLICT);
		} catch (RuntimeException failure) {
			// Include failures at commit, not only flush; discard potentially sensitive diagnostics.
			throw new ReservationProcessStateException(Reason.STORAGE_FAILURE);
		}
	}

	private static void requireIdentity(long id, AuthenticatedReservationUser owner) {
		Objects.requireNonNull(owner, "Authenticated owner is required");
		if (id <= 0) {
			throw new IllegalArgumentException("A positive local process ID is required");
		}
	}

	private static StoredReservationProcess observation(ReservationProcessEntity process) {
		var hold = process.getStatus() == ReservationProcessStatus.PENDING_HOLD ? null
				: new AppointmentHold(process.getHoldId(), process.getExternalReservationProcessId(), process.getExpiresAt());
		return new StoredReservationProcess(process.getId(), process.getVersion(), process.getStatus(),
				new StartReservationCommand(process.getProfessionalId(), process.getAppointmentDate(), process.getStartTime()),
				process.getExternalPatientId(), hold, process.getPatientFirstName(), process.getPatientLastName());
	}

	@Override
	public HeldReservationProcess createHeld(AuthenticatedReservationUser owner, StartReservationCommand selection,
			AppointmentHold hold) {
		Objects.requireNonNull(owner, "Authenticated owner is required");
		Objects.requireNonNull(selection, "Appointment selection is required");
		Objects.requireNonNull(hold, "Successful appointment hold is required");
		try {
			// Catch outside the transaction: a successful flush alone does not prove that commit succeeded.
			return transactions.execute(status -> persist(owner, selection, hold));
		} catch (RuntimeException failure) {
			// SQL/transaction diagnostics may include data or credentials; do not retain their causes.
			throw new ReservationPersistenceException();
		}
	}

	private HeldReservationProcess persist(AuthenticatedReservationUser owner, StartReservationCommand selection,
			AppointmentHold hold) {
		var user = users.getReferenceById(owner.userId());
		var process = new ReservationProcessEntity(user, selection.professionalId(), selection.date(), selection.startTime(),
				ExternalPatientId.fromUserId(owner.userId()), owner.login());
		// PostgreSQL timestamps have microsecond precision: truncate instead of rounding the remote expiry up.
		var storedHold = new AppointmentHold(hold.holdId(), hold.reservationProcessId(),
				hold.expiresAt().truncatedTo(ChronoUnit.MICROS));
		process.markHeld(storedHold.holdId(), storedHold.reservationProcessId(), storedHold.expiresAt(), owner.login());
		var saved = processes.saveAndFlush(process);
		return new HeldReservationProcess(saved.getId(), storedHold);
	}
}
