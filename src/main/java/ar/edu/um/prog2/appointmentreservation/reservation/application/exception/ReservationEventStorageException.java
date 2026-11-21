package ar.edu.um.prog2.appointmentreservation.reservation.application.exception;

/** Discards SQL/serialization causes and never implies that an event was applied. */
public final class ReservationEventStorageException extends RuntimeException {
	public ReservationEventStorageException() { super("Reservation event storage operation could not be completed"); }
}
