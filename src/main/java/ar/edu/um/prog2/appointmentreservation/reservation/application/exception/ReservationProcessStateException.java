package ar.edu.um.prog2.appointmentreservation.reservation.application.exception;

/** Local persistence outcome only; it makes no assertion about a previous remote operation. */
public final class ReservationProcessStateException extends RuntimeException {

	public enum Reason {
		NOT_FOUND,
		CONFLICT,
		STORAGE_FAILURE
	}

	private final Reason reason;

	public ReservationProcessStateException(Reason reason) {
		super(switch (reason) {
			case NOT_FOUND -> "Reservation process was not found for the authenticated user";
			case CONFLICT -> "Reservation process state no longer matches the expected observation";
			case STORAGE_FAILURE -> "Reservation process storage failed; the remote outcome cannot be determined from this local failure";
		});
		this.reason = reason;
	}

	public Reason reason() {
		return reason;
	}
}
