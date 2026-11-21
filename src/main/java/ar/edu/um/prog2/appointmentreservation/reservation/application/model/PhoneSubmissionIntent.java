package ar.edu.um.prog2.appointmentreservation.reservation.application.model;

import java.time.Instant;

/** Immutable internal observation. Never return this personal/technical payload directly to KMP. */
public record PhoneSubmissionIntent(String eventId, long localProcessId, String requestEventId,
		String externalProcessId, String holdId, String technicalGroupId, String normalizedPhone,
		Instant occurredAt, State state) {
	public enum State { PENDING, PUBLISHED, CLOSED }
	@Override
	public String toString() { return "PhoneSubmissionIntent[redacted]"; }
}
