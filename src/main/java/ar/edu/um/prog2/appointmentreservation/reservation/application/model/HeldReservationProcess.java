package ar.edu.um.prog2.appointmentreservation.reservation.application.model;

/** Result of local HELD persistence; the local identity remains distinct from the remote process ID. */
public record HeldReservationProcess(long id, AppointmentHold hold) {

	public HeldReservationProcess {
		if (id <= 0 || hold == null) {
			throw new IllegalArgumentException("A persisted local process and its hold are required");
		}
	}
}
