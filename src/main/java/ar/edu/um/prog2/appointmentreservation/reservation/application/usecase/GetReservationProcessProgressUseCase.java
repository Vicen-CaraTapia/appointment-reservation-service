package ar.edu.um.prog2.appointmentreservation.reservation.application.usecase;

import java.time.Clock;
import java.util.Objects;
import ar.edu.um.prog2.appointmentreservation.reservation.application.exception.ReservationProcessStateException;
import ar.edu.um.prog2.appointmentreservation.reservation.application.model.ReservationProcessProgress;
import ar.edu.um.prog2.appointmentreservation.reservation.application.model.ReservationProcessProgress.RequiredAction;
import ar.edu.um.prog2.appointmentreservation.reservation.application.model.ReservationProcessProgress.Reason;
import ar.edu.um.prog2.appointmentreservation.reservation.application.port.out.CurrentReservationUser;
import ar.edu.um.prog2.appointmentreservation.reservation.application.port.out.ReservationProcessStore;
import ar.edu.um.prog2.appointmentreservation.reservation.domain.model.ReservationProcessStatus;

/** Observes committed local progress without applying events or making network calls. */
public final class GetReservationProcessProgressUseCase {
	private final CurrentReservationUser currentUser;
	private final ReservationProcessStore processes;
	private final Clock clock;
	public GetReservationProcessProgressUseCase(CurrentReservationUser currentUser, ReservationProcessStore processes, Clock clock) {
		this.currentUser = Objects.requireNonNull(currentUser);
		this.processes = Objects.requireNonNull(processes);
		this.clock = Objects.requireNonNull(clock);
	}
	public ReservationProcessProgress get(long id) {
		var owner = currentUser.requireAuthenticated();
		if (id <= 0) { throw new IllegalArgumentException("A positive local process ID is required"); }
		var process = processes.findOwnedProgress(id, owner).orElseThrow(() ->
				new ReservationProcessStateException(ReservationProcessStateException.Reason.NOT_FOUND));
		Reason reason = switch (process.reason() == null ? "" : process.reason()) {
			case "INVALID_PHONE_NUMBER" -> Reason.INVALID_PHONE_NUMBER;
			case "HOLD_EXPIRED" -> Reason.HOLD_EXPIRED;
			case "REQUEST_EVENT_MISMATCH" -> Reason.REQUEST_EVENT_MISMATCH;
			default -> null;
		};
		var action = RequiredAction.NONE;
		if (process.status() == ReservationProcessStatus.WAITING_FOR_PHONE && process.phoneRequestReady()
				&& process.expiresAt() != null && process.expiresAt().isAfter(clock.instant())) {
			if (process.reason() == null) { action = RequiredAction.SUBMIT_PHONE; }
			else if (reason == Reason.INVALID_PHONE_NUMBER) { action = RequiredAction.CORRECT_PHONE; }
		}
		return new ReservationProcessProgress(process.id(), process.status(), process.expiresAt(), action, reason);
	}
}
