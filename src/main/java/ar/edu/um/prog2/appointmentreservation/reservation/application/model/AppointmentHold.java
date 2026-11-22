package ar.edu.um.prog2.appointmentreservation.reservation.application.model;

import java.time.Instant;

/** Successful remote hold; its authoritative expiration is preserved, not extended or recalculated. */
public record AppointmentHold(String holdId, String reservationProcessId, Instant expiresAt) {

	public AppointmentHold {
		if (holdId == null || holdId.isBlank() || reservationProcessId == null
				|| reservationProcessId.isBlank() || expiresAt == null) {
			throw new IllegalArgumentException("A complete successful appointment hold is required");
		}
	}

	@Override
	public String toString() {
		return "AppointmentHold[redacted]";
	}
}
