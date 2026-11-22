package ar.edu.um.prog2.appointmentreservation.reservation.application.model;

import java.util.UUID;

/** Fences completion of a single publication attempt after its local transaction commits. */
public record PhonePublicationClaim(UUID token, PhoneSubmissionIntent intent) {
	@Override
	public String toString() { return "PhonePublicationClaim[redacted]"; }
}
