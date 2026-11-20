package ar.edu.um.prog2.appointmentreservation.reservation.infrastructure.persistence;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

import ar.edu.um.prog2.appointmentreservation.reservation.application.exception.ReservationEventStorageException;
import ar.edu.um.prog2.appointmentreservation.reservation.application.model.ReservationActionEvent;
import ar.edu.um.prog2.appointmentreservation.reservation.application.model.ReservationEventReceipt;
import ar.edu.um.prog2.appointmentreservation.reservation.application.model.ReservationEventReceipt.State;
import ar.edu.um.prog2.appointmentreservation.reservation.application.port.out.ReservationEventStore;
import ar.edu.um.prog2.appointmentreservation.reservation.infrastructure.persistence.repository.ReservationEventInboxJpaRepository;
import ar.edu.um.prog2.appointmentreservation.reservation.infrastructure.persistence.repository.ReservationProcessJpaRepository;
import ar.edu.um.prog2.appointmentreservation.reservation.infrastructure.persistence.repository.PhoneSubmissionJpaRepository;
import ar.edu.um.prog2.appointmentreservation.reservation.domain.model.AsynchronousReservationTransitions.Outcome;
import ar.edu.um.prog2.appointmentreservation.reservation.domain.model.AsynchronousReservationTransitions.Signal;

/** Bounded local operations. Row locks serialize event identity first, then its local process. */
public final class JpaReservationEventAdapter implements ReservationEventStore {
	private final ReservationEventInboxJpaRepository inbox;
	private final ReservationProcessJpaRepository processes;
	private final TransactionTemplate transactions;
	private final PhoneSubmissionJpaRepository submissions;
	// Fixed persistence format: HTTP naming/pretty-printing settings must not change event identity.
	private final JsonMapper json = JsonMapper.builder().enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
			.disable(SerializationFeature.INDENT_OUTPUT).build();

	public JpaReservationEventAdapter(ReservationEventInboxJpaRepository inbox, ReservationProcessJpaRepository processes,
			PhoneSubmissionJpaRepository submissions, PlatformTransactionManager transactionManager) {
		this.inbox = Objects.requireNonNull(inbox);
		this.processes = Objects.requireNonNull(processes);
		this.transactions = new TransactionTemplate(Objects.requireNonNull(transactionManager));
		this.submissions = Objects.requireNonNull(submissions);
		this.transactions.setTimeout(10);
	}

	@Override
	public ReservationEventReceipt receive(ReservationActionEvent event) {
		Objects.requireNonNull(event, "Validated reservation event is required");
		try {
			String payload = json.writeValueAsString(event);
			String fingerprint = fingerprint(payload);
			return transactions.execute(status -> {
				inbox.insertIfAbsent(event.eventId(), event.externalProcessId(), payload, fingerprint);
				var stored = inbox.findForUpdate(event.eventId()).orElseThrow();
				if (!stored.getFingerprint().equals(fingerprint)) {
					stored.recordContentConflict(fingerprint);
				}
				return stored.receipt();
			});
		} catch (RuntimeException failure) {
			throw new ReservationEventStorageException();
		}
	}

	@Override
	public ReservationEventReceipt applyPending(String eventId) {
		requireId(eventId);
		try {
			return transactions.execute(status -> {
				var stored = inbox.findForUpdate(eventId).orElseThrow();
				if (stored.getState() != State.PENDING || stored.hasContentConflict()) {
					return stored.receipt();
				}
				var event = json.readValue(stored.getPayload(), ReservationActionEvent.class);
				var process = processes.findByExternalIdForUpdate(event.externalProcessId());
				if (process.isEmpty()) { return stored.receipt(); }
				var owned = process.orElseThrow();
				var decision = owned.applyAsynchronousEvidence(event);
				if (decision.outcome() == Outcome.APPLY && owned.getCurrentPhoneSubmissionEventId() != null
						&& (owned.getStatus().isTerminal() || event.signal() == Signal.PHONE_REJECTED)) {
					submissions.findForUpdate(owned.getCurrentPhoneSubmissionEventId()).orElseThrow().close(Instant.now());
				}
				switch (decision.outcome()) {
					case APPLY -> stored.handled(State.APPLIED, owned.getId());
					case IGNORE -> stored.handled(State.IGNORED, owned.getId());
					case CONFLICT -> stored.handled(State.CONFLICT, owned.getId());
					case DEFER -> { /* Keep durable, discoverable evidence without claiming application. */ }
				}
				processes.flush();
				inbox.flush();
				return stored.receipt();
			});
		} catch (RuntimeException failure) {
			throw new ReservationEventStorageException();
		}
	}

	@Override
	public List<String> findPending(String externalProcessId, int limit) {
		requireId(externalProcessId);
		if (limit < 1 || limit > 100) { throw new IllegalArgumentException("Pending event batch must contain 1 to 100 entries"); }
		try {
			return List.copyOf(inbox.findPending(externalProcessId, State.PENDING, PageRequest.of(0, limit)));
		} catch (RuntimeException failure) {
			throw new ReservationEventStorageException();
		}
	}

	private static void requireId(String id) {
		if (id == null || id.isBlank()) { throw new IllegalArgumentException("Reservation event identifier is required"); }
	}

	private static String fingerprint(String payload) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
					.digest(("reservation-event-v1:" + payload).getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException failure) {
			throw new IllegalStateException("Reservation event fingerprint algorithm is unavailable");
		}
	}
}
