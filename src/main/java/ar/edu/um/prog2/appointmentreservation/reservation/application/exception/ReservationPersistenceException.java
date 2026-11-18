package ar.edu.um.prog2.appointmentreservation.reservation.application.exception;

/** A local save failed; this failure does not roll back or disprove the remote hold. */
public final class ReservationPersistenceException extends RuntimeException {

	public ReservationPersistenceException() {
		super("Reservation process could not be persisted; the remote hold may still exist");
	}
}
