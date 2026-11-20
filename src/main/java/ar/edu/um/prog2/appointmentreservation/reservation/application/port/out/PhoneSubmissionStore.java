package ar.edu.um.prog2.appointmentreservation.reservation.application.port.out;

import java.time.Instant;
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
	/** Invoke only after broker acknowledgment, never merely after calling send. */
	PhoneSubmissionIntent recordPublished(String eventId, Instant acknowledgedAt);
}
