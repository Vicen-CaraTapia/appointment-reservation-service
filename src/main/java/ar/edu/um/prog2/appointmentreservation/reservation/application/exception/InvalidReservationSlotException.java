package ar.edu.um.prog2.appointmentreservation.reservation.application.exception;

/** The selected time is not a complete slot in the current enabled schedule. */
public final class InvalidReservationSlotException extends RuntimeException {

	public InvalidReservationSlotException() {
		super("The selected appointment slot is not valid in the current professional schedule");
	}
}
