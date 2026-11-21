package ar.edu.um.prog2.appointmentreservation.reservation.application.usecase;

import java.util.List;
import java.util.Objects;

import ar.edu.um.prog2.appointmentreservation.reservation.application.model.ReservationActionEvent;
import ar.edu.um.prog2.appointmentreservation.reservation.application.model.ReservationEventReceipt;
import ar.edu.um.prog2.appointmentreservation.reservation.application.model.ReservationEventReceipt.State;
import ar.edu.um.prog2.appointmentreservation.reservation.application.port.out.ReservationEventStore;

/** Coordinates committed local operations. The transport decides offsets only after this returns. */
public final class ProcessReservationActionUseCase {
	private static final int MAX_PENDING_BATCH = 100;
	private final ReservationEventStore events;

	public ProcessReservationActionUseCase(ReservationEventStore events) {
		this.events = Objects.requireNonNull(events);
	}

	public ReservationEventReceipt process(ReservationActionEvent event) {
		Objects.requireNonNull(event, "Validated reservation action is required");
		var receipt = events.receive(event);
		if (receipt.state() == State.PENDING && !receipt.contentConflict()) {
			receipt = events.applyPending(event.eventId());
		}
		if (receipt.state() == State.APPLIED && !receipt.contentConflict()) {
			// New evidence may unlock an earlier event. Replays also resume work interrupted after its commit.
			resumePending(event.externalProcessId(), MAX_PENDING_BATCH);
		}
		return receipt;
	}

	/** One bounded pass; still-inapplicable evidence remains durable for a later invocation. */
	public List<ReservationEventReceipt> resumePending(String externalProcessId, int limit) {
		if (externalProcessId == null || externalProcessId.isBlank() || limit < 1 || limit > MAX_PENDING_BATCH) {
			throw new IllegalArgumentException("A process identifier and a pending batch of 1 to 100 are required");
		}
		return events.findPending(externalProcessId, limit).stream().map(events::applyPending).toList();
	}
}
