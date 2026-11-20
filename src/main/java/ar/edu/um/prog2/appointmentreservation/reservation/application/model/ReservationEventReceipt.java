package ar.edu.um.prog2.appointmentreservation.reservation.application.model;

/** Original receipt state remains intact when an incompatible redelivery is detected. */
public record ReservationEventReceipt(String eventId, State state, Long localProcessId, boolean contentConflict) {
	public enum State { PENDING, APPLIED, IGNORED, CONFLICT }

	@Override
	public String toString() { return "ReservationEventReceipt[redacted]"; }
}
