package ar.edu.um.prog2.appointmentreservation.reservation.application.model;

import java.time.Instant;
import java.util.Objects;

import ar.edu.um.prog2.appointmentreservation.reservation.domain.model.AsynchronousReservationTransitions.Signal;

/** Validated application evidence, not a Kafka envelope. Contains no owner, token, name or phone. */
public record ReservationActionEvent(String eventId, Signal signal, String externalProcessId, String holdId,
		String requestEventId, Instant occurredAt, Instant expiresAt, Long externalReservationId,
		StartReservationCommand confirmedSelection, String reason) {

	public ReservationActionEvent {
		if (!identifier(eventId) || !identifier(externalProcessId) || !identifier(holdId)) {
			throw new IllegalArgumentException("Reservation event identifiers are required");
		}
		Objects.requireNonNull(signal, "Reservation event signal is required");
		Objects.requireNonNull(occurredAt, "Reservation event occurrence time is required");
		boolean valid = switch (signal) {
			case PHONE_REQUESTED -> requestEventId == null && expiresAt != null && externalReservationId == null
					&& confirmedSelection == null && reason == null;
			case RESERVATION_CONFIRMED -> identifier(requestEventId) && expiresAt == null && externalReservationId != null
					&& externalReservationId > 0 && confirmedSelection != null && reason == null;
			case RESERVATION_CANCELLED -> identifier(requestEventId) && expiresAt == null && externalReservationId != null
					&& externalReservationId > 0 && confirmedSelection != null && (reason == null || reason.length() <= 500);
			case PHONE_REJECTED -> identifier(requestEventId) && expiresAt != null && externalReservationId == null
					&& confirmedSelection == null && code(reason);
			case PROCESS_EXPIRED, PROCESS_INVALID -> identifier(requestEventId) && expiresAt == null
					&& externalReservationId == null && confirmedSelection == null && code(reason);
			default -> false;
		};
		if (!valid) {
			throw new IllegalArgumentException("Reservation event evidence is incomplete or incompatible");
		}
	}

	private static boolean identifier(String value) { return value != null && !value.isBlank(); }
	private static boolean code(String value) { return value != null && value.matches("[A-Z][A-Z0-9_]*"); }

	/** Unknown well-formed reasons remain durable evidence and require explicit contract review. */
	public boolean hasSupportedReason() {
		return switch (signal) {
			case PHONE_REJECTED -> "INVALID_PHONE_NUMBER".equals(reason);
			case PROCESS_EXPIRED -> "HOLD_EXPIRED".equals(reason);
			case PROCESS_INVALID -> "REQUEST_EVENT_MISMATCH".equals(reason);
			default -> true;
		};
	}

	@Override
	public String toString() { return "ReservationActionEvent[redacted]"; }
}
