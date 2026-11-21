package ar.edu.um.prog2.appointmentreservation.reservation.application.usecase;

import java.time.Clock;
import java.util.Objects;
import java.util.UUID;

import ar.edu.um.prog2.appointmentreservation.reservation.application.exception.InvalidPhoneNumberException;
import ar.edu.um.prog2.appointmentreservation.reservation.application.model.NewPhoneSubmission;
import ar.edu.um.prog2.appointmentreservation.reservation.application.model.PhoneSubmissionIntent;
import ar.edu.um.prog2.appointmentreservation.reservation.application.port.out.CurrentReservationUser;
import ar.edu.um.prog2.appointmentreservation.reservation.application.port.out.PhoneSubmissionStore;

/** Prepares a durable intent; ownership, correlation and concurrent replay are checked atomically by the store. */
public final class PreparePhoneSubmissionUseCase {
	private final CurrentReservationUser currentUser;
	private final PhoneSubmissionStore submissions;
	private final String technicalGroupId;
	private final Clock clock;

	public PreparePhoneSubmissionUseCase(CurrentReservationUser currentUser, PhoneSubmissionStore submissions,
			String technicalGroupId, Clock clock) {
		this.currentUser = Objects.requireNonNull(currentUser);
		this.submissions = Objects.requireNonNull(submissions);
		if (technicalGroupId == null || technicalGroupId.isBlank()) {
			throw new IllegalArgumentException("Technical integration group is required");
		}
		this.technicalGroupId = technicalGroupId;
		this.clock = Objects.requireNonNull(clock);
	}

	public PhoneSubmissionIntent prepare(long localProcessId, String phoneNumber) {
		var owner = currentUser.requireAuthenticated();
		if (localProcessId <= 0) {
			throw new IllegalArgumentException("A positive local process identifier is required");
		}
		String normalized = normalize(phoneNumber);
		// A replay returns the stored event ID and timestamp, not this candidate UUID.
		return submissions.prepare(owner, new NewPhoneSubmission(localProcessId, UUID.randomUUID().toString(),
				normalized, technicalGroupId), clock.instant());
	}

	private static String normalize(String phoneNumber) {
		if (phoneNumber == null) { throw new InvalidPhoneNumberException(); }
		String normalized = phoneNumber.replaceAll("[\\s().-]", "");
		if (!normalized.matches("\\+?[0-9]{7,15}")) { throw new InvalidPhoneNumberException(); }
		return normalized;
	}
}
