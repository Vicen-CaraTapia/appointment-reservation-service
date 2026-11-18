package ar.edu.um.prog2.appointmentreservation.reservation.application.exception;

/** Application failure independent of Spring Security and the future HTTP error mapping. */
public final class ReservationAuthenticationRequiredException extends RuntimeException {

	public ReservationAuthenticationRequiredException() {
		super("An authenticated final user is required to start a reservation process");
	}
}
