package ar.edu.um.prog2.appointmentreservation.reservation.application.usecase;

import java.util.Objects;

import ar.edu.um.prog2.appointmentreservation.reservation.application.ExternalPatientId;
import ar.edu.um.prog2.appointmentreservation.reservation.application.model.HeldReservationProcess;
import ar.edu.um.prog2.appointmentreservation.reservation.application.model.StartReservationCommand;
import ar.edu.um.prog2.appointmentreservation.reservation.application.port.out.AppointmentHoldRequester;
import ar.edu.um.prog2.appointmentreservation.reservation.application.port.out.CurrentReservationUser;
import ar.edu.um.prog2.appointmentreservation.reservation.application.port.out.ReservationProcessStore;
import ar.edu.um.prog2.appointmentreservation.reservation.application.service.ReservationSlotValidator;

/** Coordinates remote work before the local store's transaction; no distributed transaction or blind retry. */
public final class StartReservationProcessUseCase {

	private final CurrentReservationUser currentUser;
	private final ReservationSlotValidator slots;
	private final AppointmentHoldRequester holds;
	private final ReservationProcessStore processes;

	public StartReservationProcessUseCase(CurrentReservationUser currentUser, ReservationSlotValidator slots,
			AppointmentHoldRequester holds, ReservationProcessStore processes) {
		this.currentUser = Objects.requireNonNull(currentUser);
		this.slots = Objects.requireNonNull(slots);
		this.holds = Objects.requireNonNull(holds);
		this.processes = Objects.requireNonNull(processes);
	}

	public HeldReservationProcess start(StartReservationCommand selection) {
		Objects.requireNonNull(selection, "Appointment selection is required");
		var owner = currentUser.requireAuthenticated();
		slots.validate(selection);
		var hold = holds.createHold(selection, ExternalPatientId.fromUserId(owner.userId()));
		// A failed local save cannot undo this remote hold. Propagate its controlled failure without retrying.
		return processes.createHeld(owner, selection, hold);
	}
}
