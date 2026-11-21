package ar.edu.um.prog2.appointmentreservation.reservation.application.model;

import java.time.Instant;
import ar.edu.um.prog2.appointmentreservation.reservation.domain.model.ReservationProcessStatus;

public record ReservationProcessProgress(long id, ReservationProcessStatus status, Instant expiresAt,
		RequiredAction requiredAction, Reason reason) {
	public enum RequiredAction { NONE, SUBMIT_PHONE, CORRECT_PHONE }
	public enum Reason { INVALID_PHONE_NUMBER, HOLD_EXPIRED, REQUEST_EVENT_MISMATCH }
}
