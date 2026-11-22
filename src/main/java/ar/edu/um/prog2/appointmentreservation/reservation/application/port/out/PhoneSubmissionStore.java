package ar.edu.um.prog2.appointmentreservation.reservation.application.port.out;

import java.time.Instant;
import java.time.Duration;
import java.util.UUID;
import ar.edu.um.prog2.appointmentreservation.reservation.application.model.PhonePublicationClaim;
import java.util.List;
import java.util.Optional;

import ar.edu.um.prog2.appointmentreservation.reservation.application.model.AuthenticatedReservationUser;
import ar.edu.um.prog2.appointmentreservation.reservation.application.model.NewPhoneSubmission;
import ar.edu.um.prog2.appointmentreservation.reservation.application.model.PhoneSubmissionIntent;

/** Local durable-intent boundary. A8.3 owns the actual publisher and bounded retry coordination. */
public interface PhoneSubmissionStore {
	PhoneSubmissionIntent prepare(AuthenticatedReservationUser owner, NewPhoneSubmission submission, Instant now);
	Optional<PhoneSubmissionIntent> findOwned(long localProcessId, AuthenticatedReservationUser owner);
	List<String> findPending(Instant now, int limit);
	Optional<PhoneSubmissionIntent> findPublishable(String eventId, Instant now);
	List<String> findReady(Instant now, int limit, int maxAttempts);
	Optional<PhonePublicationClaim> claimPublication(String eventId, Instant now, Duration lease, int maxAttempts);
	void completePublication(String eventId, UUID token, Instant acknowledgedAt);
	void deferPublication(String eventId, UUID token, Instant nextAttemptAt);
	/** Invoke only after broker acknowledgment, never merely after calling send. */
	PhoneSubmissionIntent recordPublished(String eventId, Instant acknowledgedAt);
}
