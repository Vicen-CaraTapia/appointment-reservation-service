package ar.edu.um.prog2.appointmentreservation.reservation.domain.model;

/** Local workflow observations, separate from the cátedra's transport status values. */
public enum ReservationProcessStatus {
	PENDING_HOLD,
	HELD,
	CONFIRMATION_PENDING,
	WAITING_FOR_PHONE,
	PHONE_SUBMISSION_PENDING,
	WAITING_FOR_CONFIRMATION,
	CONFIRMED,
	EXPIRED,
	INVALID;

	public boolean isTerminal() {
		return this == CONFIRMED || this == EXPIRED || this == INVALID;
	}
}
