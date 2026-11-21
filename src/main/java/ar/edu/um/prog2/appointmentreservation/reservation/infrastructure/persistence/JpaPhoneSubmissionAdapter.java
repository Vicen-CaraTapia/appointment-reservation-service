package ar.edu.um.prog2.appointmentreservation.reservation.infrastructure.persistence;

import java.time.Instant;
import java.time.Duration;
import java.util.UUID;
import ar.edu.um.prog2.appointmentreservation.reservation.application.model.PhonePublicationClaim;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import ar.edu.um.prog2.appointmentreservation.reservation.application.exception.PhoneSubmissionStateException;
import ar.edu.um.prog2.appointmentreservation.reservation.application.exception.PhoneSubmissionStateException.Reason;
import ar.edu.um.prog2.appointmentreservation.reservation.application.model.AuthenticatedReservationUser;
import ar.edu.um.prog2.appointmentreservation.reservation.application.model.NewPhoneSubmission;
import ar.edu.um.prog2.appointmentreservation.reservation.application.model.PhoneSubmissionIntent;
import ar.edu.um.prog2.appointmentreservation.reservation.application.model.PhoneSubmissionIntent.State;
import ar.edu.um.prog2.appointmentreservation.reservation.application.model.ReservationEventReceipt;
import ar.edu.um.prog2.appointmentreservation.reservation.application.port.out.PhoneSubmissionStore;
import ar.edu.um.prog2.appointmentreservation.reservation.domain.model.ReservationProcessStatus;
import ar.edu.um.prog2.appointmentreservation.reservation.infrastructure.persistence.entity.PhoneSubmissionEntity;
import ar.edu.um.prog2.appointmentreservation.reservation.infrastructure.persistence.entity.ReservationProcessEntity;
import ar.edu.um.prog2.appointmentreservation.reservation.infrastructure.persistence.repository.PhoneSubmissionJpaRepository;
import ar.edu.um.prog2.appointmentreservation.reservation.infrastructure.persistence.repository.ReservationEventInboxJpaRepository;
import ar.edu.um.prog2.appointmentreservation.reservation.infrastructure.persistence.repository.ReservationProcessJpaRepository;

/** Locks process before intent, consistent with inbox -> process -> intent ordering. No network operation. */
public final class JpaPhoneSubmissionAdapter implements PhoneSubmissionStore {
	private final ReservationProcessJpaRepository processes;
	private final PhoneSubmissionJpaRepository submissions;
	private final ReservationEventInboxJpaRepository inbox;
	private final TransactionTemplate transactions;

	public JpaPhoneSubmissionAdapter(ReservationProcessJpaRepository processes, PhoneSubmissionJpaRepository submissions,
			ReservationEventInboxJpaRepository inbox, PlatformTransactionManager transactionManager) {
		this.processes = Objects.requireNonNull(processes);
		this.submissions = Objects.requireNonNull(submissions);
		this.inbox = Objects.requireNonNull(inbox);
		this.transactions = new TransactionTemplate(Objects.requireNonNull(transactionManager));
		this.transactions.setTimeout(10);
	}

	@Override
	public PhoneSubmissionIntent prepare(AuthenticatedReservationUser owner, NewPhoneSubmission input, Instant now) {
		Objects.requireNonNull(owner, "Authenticated owner is required");
		Objects.requireNonNull(input, "Backend submission metadata is required");
		Objects.requireNonNull(now, "Current time is required");
		try {
			return transactions.execute(transaction -> prepareOwned(owner, input, now));
		} catch (PhoneSubmissionStateException failure) {
			throw failure;
		} catch (RuntimeException failure) {
			throw failure(Reason.STORAGE_FAILURE);
		}
	}

	private PhoneSubmissionIntent prepareOwned(AuthenticatedReservationUser owner, NewPhoneSubmission input, Instant now) {
		var process = processes.findOwnedForUpdate(input.localProcessId(), owner.userId())
				.orElseThrow(() -> failure(Reason.NOT_FOUND));
		if (process.getStatus().isTerminal()) { throw failure(Reason.CONFLICT); }
		var current = current(process);
		if (current.isPresent()) {
			var previous = current.orElseThrow();
			if (!previous.sameCorrelation(process, input.technicalGroupId())) { throw failure(Reason.CONFLICT); }
			if (previous.matches(process, input)) { return previous.observation(); }
			if (previous.getState() != State.CLOSED) { throw failure(Reason.CONFLICT); }
		}
		if (process.getStatus() != ReservationProcessStatus.WAITING_FOR_PHONE || process.getRequestEventId() == null) {
			throw failure(Reason.CONFLICT);
		}
		if (!process.getExpiresAt().isAfter(now)) { throw failure(Reason.EXPIRED); }
		if (!requestUsable(process)) { throw failure(Reason.CONFLICT); }
		if (submissions.existsById(input.eventId())) { throw failure(Reason.CONFLICT); }
		var intent = submissions.saveAndFlush(new PhoneSubmissionEntity(process, input, now));
		process.preparePhoneSubmission(intent.getEventId(), owner.login());
		processes.flush();
		return intent.observation();
	}

	@Override
	public Optional<PhoneSubmissionIntent> findOwned(long localProcessId, AuthenticatedReservationUser owner) {
		Objects.requireNonNull(owner, "Authenticated owner is required");
		try {
			return transactions.execute(transaction -> processes.findOwnedForUpdate(localProcessId, owner.userId())
					.flatMap(this::current).map(PhoneSubmissionEntity::observation));
		} catch (RuntimeException failure) { throw failure(Reason.STORAGE_FAILURE); }
	}

	@Override
	public List<String> findPending(Instant now, int limit) {
		Objects.requireNonNull(now, "Current time is required");
		if (limit < 1 || limit > 100) { throw new IllegalArgumentException("Pending submission batch must contain 1 to 100 entries"); }
		try {
			return List.copyOf(submissions.findPending(now, State.PENDING, ReservationProcessStatus.PHONE_SUBMISSION_PENDING,
					ReservationEventReceipt.State.APPLIED, PageRequest.of(0, limit)));
		} catch (RuntimeException failure) { throw failure(Reason.STORAGE_FAILURE); }
	}

	@Override
	public Optional<PhoneSubmissionIntent> findPublishable(String eventId, Instant now) {
		requireEventId(eventId);
		Objects.requireNonNull(now, "Current time is required");
		try {
			return transactions.execute(transaction -> {
				var initial = submissions.findProcessId(eventId);
				if (initial.isEmpty()) { return Optional.empty(); }
				var process = processes.findByIdForUpdate(initial.orElseThrow()).orElseThrow();
				return current(process).filter(intent -> eventId.equals(intent.getEventId()) && intent.getState() == State.PENDING
						&& process.getStatus() == ReservationProcessStatus.PHONE_SUBMISSION_PENDING && process.getExpiresAt().isAfter(now)
						&& requestUsable(process))
						.map(PhoneSubmissionEntity::observation);
			});
		} catch (RuntimeException failure) { throw failure(Reason.STORAGE_FAILURE); }
	}

	@Override
	public PhoneSubmissionIntent recordPublished(String eventId, Instant acknowledgedAt) {
		requireEventId(eventId);
		Objects.requireNonNull(acknowledgedAt, "Acknowledgment time is required");
		try {
			return transactions.execute(transaction -> {
				// Read immutable routing first; refresh the mutable row after taking the process lock.
				long processId = submissions.findProcessId(eventId).orElseThrow();
				var process = processes.findByIdForUpdate(processId).orElseThrow();
				var intent = submissions.findForUpdate(eventId).orElseThrow();
				if (intent.getState() == State.PENDING) {
					if (eventId.equals(process.getCurrentPhoneSubmissionEventId())
							&& process.getStatus() == ReservationProcessStatus.PHONE_SUBMISSION_PENDING) {
						intent.published(acknowledgedAt);
						process.recordPhonePublication(eventId);
					} else { intent.close(acknowledgedAt); }
				}
				processes.flush();
				return intent.observation();
			});
		} catch (RuntimeException failure) { throw failure(Reason.STORAGE_FAILURE); }
	}

	@Override
	public List<String> findReady(Instant now, int limit, int maxAttempts) {
		if (now == null || limit < 1 || limit > 100 || maxAttempts < 1) {
			throw new IllegalArgumentException("Valid publication recovery limits are required");
		}
		try {
			return List.copyOf(submissions.findReady(now, maxAttempts, State.PENDING,
					ReservationProcessStatus.PHONE_SUBMISSION_PENDING, ReservationEventReceipt.State.APPLIED, PageRequest.of(0, limit)));
		} catch (RuntimeException failure) { throw failure(Reason.STORAGE_FAILURE); }
	}

	@Override
	public Optional<PhonePublicationClaim> claimPublication(String eventId, Instant now, Duration lease, int maxAttempts) {
		requireEventId(eventId);
		if (now == null || lease == null || lease.isNegative() || lease.isZero() || maxAttempts < 1) {
			throw new IllegalArgumentException("Valid publication lease and attempt limit are required");
		}
		try {
			return transactions.execute(transaction -> {
				var processId = submissions.findProcessId(eventId);
				if (processId.isEmpty()) { return Optional.empty(); }
				var process = processes.findByIdForUpdate(processId.orElseThrow()).orElseThrow();
				return current(process).filter(intent -> eventId.equals(intent.getEventId()) && intent.ready(now, maxAttempts)
						&& process.getStatus() == ReservationProcessStatus.PHONE_SUBMISSION_PENDING
						&& process.getExpiresAt().isAfter(now) && requestUsable(process))
						.map(intent -> new PhonePublicationClaim(intent.claim(now.plus(lease)), intent.observation()));
			});
		} catch (RuntimeException failure) { throw failure(Reason.STORAGE_FAILURE); }
	}

	@Override
	public void completePublication(String eventId, UUID token, Instant acknowledgedAt) {
		finishAttempt(eventId, token, acknowledgedAt, true);
	}

	@Override
	public void deferPublication(String eventId, UUID token, Instant nextAttemptAt) {
		finishAttempt(eventId, token, nextAttemptAt, false);
	}

	private void finishAttempt(String eventId, UUID token, Instant time, boolean acknowledged) {
		requireEventId(eventId);
		Objects.requireNonNull(token, "Publication claim is required");
		Objects.requireNonNull(time, "Publication time is required");
		try {
			transactions.executeWithoutResult(transaction -> {
				long processId = submissions.findProcessId(eventId).orElseThrow();
				var process = processes.findByIdForUpdate(processId).orElseThrow();
				var intent = submissions.findForUpdate(eventId).orElseThrow();
				if (!intent.ownsClaim(token)) { return; }
				if (acknowledged && intent.getState() == State.PENDING) {
					if (eventId.equals(process.getCurrentPhoneSubmissionEventId())
							&& process.getStatus() == ReservationProcessStatus.PHONE_SUBMISSION_PENDING) {
						intent.published(time);
						process.recordPhonePublication(eventId);
					} else { intent.close(time); }
				}
				intent.release(acknowledged ? null : time);
				processes.flush();
			});
		} catch (RuntimeException failure) { throw failure(Reason.STORAGE_FAILURE); }
	}

	private Optional<PhoneSubmissionEntity> current(ReservationProcessEntity process) {
		String eventId = process.getCurrentPhoneSubmissionEventId();
		return eventId == null ? Optional.empty() : submissions.findForUpdate(eventId);
	}
	private boolean requestUsable(ReservationProcessEntity process) {
		return inbox.findById(process.getRequestEventId()).map(entry -> {
			var receipt = entry.receipt();
			return receipt.state() == ReservationEventReceipt.State.APPLIED && !receipt.contentConflict()
					&& Objects.equals(receipt.localProcessId(), process.getId());
		}).orElse(false);
	}
	private static void requireEventId(String id) {
		if (id == null || id.isBlank()) { throw new IllegalArgumentException("Submission identifier is required"); }
	}
	private static PhoneSubmissionStateException failure(Reason reason) { return new PhoneSubmissionStateException(reason); }
}
