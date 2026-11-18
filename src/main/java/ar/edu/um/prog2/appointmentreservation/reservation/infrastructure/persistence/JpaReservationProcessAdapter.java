package ar.edu.um.prog2.appointmentreservation.reservation.infrastructure.persistence;

import java.time.temporal.ChronoUnit;
import java.util.Objects;

import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import ar.edu.um.prog2.appointmentreservation.reservation.application.ExternalPatientId;
import ar.edu.um.prog2.appointmentreservation.reservation.application.exception.ReservationPersistenceException;
import ar.edu.um.prog2.appointmentreservation.reservation.application.model.AppointmentHold;
import ar.edu.um.prog2.appointmentreservation.reservation.application.model.AuthenticatedReservationUser;
import ar.edu.um.prog2.appointmentreservation.reservation.application.model.HeldReservationProcess;
import ar.edu.um.prog2.appointmentreservation.reservation.application.model.StartReservationCommand;
import ar.edu.um.prog2.appointmentreservation.reservation.application.port.out.ReservationProcessStore;
import ar.edu.um.prog2.appointmentreservation.reservation.infrastructure.persistence.entity.ReservationProcessEntity;
import ar.edu.um.prog2.appointmentreservation.reservation.infrastructure.persistence.repository.ReservationProcessJpaRepository;
import ar.edu.um.prog2.appointmentreservation.user.infrastructure.persistence.repository.UserJpaRepository;

public final class JpaReservationProcessAdapter implements ReservationProcessStore {

	private final UserJpaRepository users;
	private final ReservationProcessJpaRepository processes;
	private final TransactionTemplate transactions;

	public JpaReservationProcessAdapter(UserJpaRepository users, ReservationProcessJpaRepository processes,
			PlatformTransactionManager transactionManager) {
		this.users = Objects.requireNonNull(users);
		this.processes = Objects.requireNonNull(processes);
		this.transactions = new TransactionTemplate(Objects.requireNonNull(transactionManager));
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
