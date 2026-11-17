package ar.edu.um.prog2.appointmentreservation.availability.application.exception;

/** An applicable current schedule cannot safely produce availability. */
public final class InvalidAvailabilityScheduleException extends IllegalArgumentException {

	public InvalidAvailabilityScheduleException() {
		super("Applicable weekly schedule must have a positive slot duration and start before end");
	}
}
