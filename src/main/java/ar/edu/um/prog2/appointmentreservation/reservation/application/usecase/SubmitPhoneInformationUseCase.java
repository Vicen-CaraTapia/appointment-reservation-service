package ar.edu.um.prog2.appointmentreservation.reservation.application.usecase;

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import ar.edu.um.prog2.appointmentreservation.reservation.application.model.PhoneSubmissionIntent;
import ar.edu.um.prog2.appointmentreservation.reservation.application.port.out.PhoneSubmissionStore;
import ar.edu.um.prog2.appointmentreservation.reservation.application.port.out.PhoneSubmissionPublisher;

/** Each store call commits independently. Broker waits never hold a database transaction. */
public final class SubmitPhoneInformationUseCase {
	private final PreparePhoneSubmissionUseCase prepare;
	private final PhoneSubmissionStore submissions;
	private final PhoneSubmissionPublisher publisher;
	private final Clock clock;
	private final int maxAttempts;
	private final Duration lease;
	private final Duration acknowledgmentTimeout;
	private final Duration retryDelay;

	public SubmitPhoneInformationUseCase(PreparePhoneSubmissionUseCase prepare, PhoneSubmissionStore submissions,
			PhoneSubmissionPublisher publisher, Clock clock, int maxAttempts, Duration lease,
			Duration acknowledgmentTimeout, Duration retryDelay) {
		this.prepare = Objects.requireNonNull(prepare);
		this.submissions = Objects.requireNonNull(submissions);
		this.publisher = Objects.requireNonNull(publisher);
		this.clock = Objects.requireNonNull(clock);
		if (maxAttempts < 1 || !positive(lease) || !positive(acknowledgmentTimeout) || !positive(retryDelay)
				|| lease.compareTo(acknowledgmentTimeout.plusSeconds(60)) <= 0) {
			throw new IllegalArgumentException("Valid bounded publication policy and lease exceeding producer wait are required");
		}
		this.maxAttempts = maxAttempts;
		this.lease = lease;
		this.acknowledgmentTimeout = acknowledgmentTimeout;
		this.retryDelay = retryDelay;
	}

	/** Returns the durable accepted intent, not a statement of final reservation success. */
	public PhoneSubmissionIntent submit(long localProcessId, String phoneNumber) {
		var intent = prepare.prepare(localProcessId, phoneNumber);
		publishPending(intent.eventId());
		return intent;
	}

	public RecoveryResult recover(int limit) {
		var pending = submissions.findReady(clock.instant(), limit, maxAttempts);
		int published = 0;
		int deferred = 0;
		for (String eventId : pending) {
			try {
				var outcome = publishPending(eventId);
				if (outcome == Outcome.PUBLISHED) { published++; }
				if (outcome == Outcome.DEFERRED) { deferred++; }
			} catch (RuntimeException storageFailure) {
				// The durable lease expires even if we cannot record this failure.
				deferred++;
			}
			if (Thread.currentThread().isInterrupted()) { break; }
		}
		return new RecoveryResult(pending.size(), published, deferred);
	}

	private Outcome publishPending(String eventId) {
		var claim = submissions.claimPublication(eventId, clock.instant(), lease, maxAttempts);
		if (claim.isEmpty()) { return Outcome.SKIPPED; }
		var attempt = claim.orElseThrow();
		try {
			publisher.publish(attempt.intent()).toCompletableFuture()
					.get(acknowledgmentTimeout.toMillis(), TimeUnit.MILLISECONDS);
		} catch (InterruptedException failure) {
			Thread.currentThread().interrupt();
			submissions.deferPublication(eventId, attempt.token(), clock.instant().plus(retryDelay));
			return Outcome.DEFERRED;
		} catch (Exception failure) {
			// Timeouts and negative acknowledgments never prove that Kafka did not receive the event.
			submissions.deferPublication(eventId, attempt.token(), clock.instant().plus(retryDelay));
			return Outcome.DEFERRED;
		}
		// A commit failure here preserves the lease and original payload for later redelivery.
		submissions.completePublication(eventId, attempt.token(), clock.instant());
		return Outcome.PUBLISHED;
	}

	private static boolean positive(Duration duration) {
		return duration != null && duration.toMillis() > 0;
	}
	private enum Outcome { SKIPPED, PUBLISHED, DEFERRED }
	public record RecoveryResult(int scanned, int published, int deferred) { }
}
