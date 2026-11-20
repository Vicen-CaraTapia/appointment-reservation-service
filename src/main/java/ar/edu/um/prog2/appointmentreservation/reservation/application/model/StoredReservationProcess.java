package ar.edu.um.prog2.appointmentreservation.reservation.application.model;

import ar.edu.um.prog2.appointmentreservation.reservation.domain.model.ReservationProcessStatus;

/** Immutable owned observation; version is an opaque concurrency token, not a workflow counter. */
public record StoredReservationProcess(long id, long version, ReservationProcessStatus status,
		StartReservationCommand selection, String externalPatientId, AppointmentHold hold,
		String patientFirstName, String patientLastName) {

	public StoredReservationProcess {
		if (id <= 0 || version < 0 || status == null || selection == null
				|| externalPatientId == null || externalPatientId.isBlank()) {
			throw new IllegalArgumentException("A complete persisted reservation process observation is required");
		}
	}

	@Override
	public String toString() {
		return "StoredReservationProcess[redacted]";
	}
}
