package ar.edu.um.prog2.appointmentreservation.reservation.domain.model;

import java.util.Objects;

/**
 * State policy only. Callers must validate ownership, correlation, current submission,
 * deadlines and event identity before applying a decision in a local transaction.
 * A decision neither acknowledges a message nor establishes durable idempotency.
 */
public final class AsynchronousReservationTransitions {

	public enum Signal {
		PHONE_REQUESTED,
		PHONE_SUBMISSION_ACCEPTED,
		PHONE_PUBLICATION_ACKNOWLEDGED,
		PHONE_REJECTED,
		RESERVATION_CONFIRMED,
		RESERVATION_CANCELLED,
		PROCESS_EXPIRED,
		PROCESS_INVALID
	}

	public enum Outcome {
		APPLY,
		DEFER,
		IGNORE,
		CONFLICT
	}

	/** Non-APPLY decisions preserve the current status; they still require durable handling. */
	public record Decision(Outcome outcome, ReservationProcessStatus nextStatus) {
		public Decision {
			Objects.requireNonNull(outcome, "Transition outcome is required");
			Objects.requireNonNull(nextStatus, "Transition status is required");
		}
	}

	private AsynchronousReservationTransitions() {
	}

	public static Decision decide(ReservationProcessStatus current, Signal signal) {
		Objects.requireNonNull(current, "Current process status is required");
		Objects.requireNonNull(signal, "Process signal is required");
		if (current == ReservationProcessStatus.CANCELLATION_PENDING) {
			return signal == Signal.RESERVATION_CANCELLED ? apply(ReservationProcessStatus.CANCELLED)
					: keep(Outcome.CONFLICT, current);
		}
		if (current.isTerminal()) {
			return forTerminal(current, signal);
		}
		if (current == ReservationProcessStatus.CONFIRMATION_PENDING && signal == Signal.PHONE_REQUESTED) {
			// A trusted, exactly correlated request proves initial acceptance even if REST was lost.
			return apply(ReservationProcessStatus.WAITING_FOR_PHONE);
		}
		boolean beforeInitialAcceptance = current == ReservationProcessStatus.PENDING_HOLD
				|| current == ReservationProcessStatus.HELD || current == ReservationProcessStatus.CONFIRMATION_PENDING;
		if (beforeInitialAcceptance) {
			// Incoming evidence may precede the REST commit; preserve it for continuation, not as applied.
			return keep(isIncoming(signal) ? Outcome.DEFER : Outcome.CONFLICT, current);
		}
		return afterInitialAcceptance(current, signal);
	}

	private static Decision forTerminal(ReservationProcessStatus current, Signal signal) {
		if (signal == Signal.PHONE_SUBMISSION_ACCEPTED) { return keep(Outcome.CONFLICT, current); }
		if (current == ReservationProcessStatus.CONFIRMED && signal == Signal.RESERVATION_CANCELLED) {
			return apply(ReservationProcessStatus.CANCELLED);
		}
		var terminal = terminalStatus(signal);
		return keep(terminal != null && terminal != current ? Outcome.CONFLICT : Outcome.IGNORE, current);
	}

	private static Decision afterInitialAcceptance(ReservationProcessStatus current, Signal signal) {
		return switch (signal) {
			case PHONE_REQUESTED -> current == ReservationProcessStatus.WAITING_FOR_PHONE
					? apply(current) : keep(Outcome.CONFLICT, current);
			case PHONE_SUBMISSION_ACCEPTED -> current == ReservationProcessStatus.WAITING_FOR_PHONE
					? apply(ReservationProcessStatus.PHONE_SUBMISSION_PENDING) : keep(Outcome.CONFLICT, current);
			case PHONE_PUBLICATION_ACKNOWLEDGED -> current == ReservationProcessStatus.PHONE_SUBMISSION_PENDING
					? apply(ReservationProcessStatus.WAITING_FOR_CONFIRMATION) : keep(Outcome.IGNORE, current);
			case PHONE_REJECTED -> current == ReservationProcessStatus.PHONE_SUBMISSION_PENDING
					|| current == ReservationProcessStatus.WAITING_FOR_CONFIRMATION
					? apply(ReservationProcessStatus.WAITING_FOR_PHONE) : keep(Outcome.CONFLICT, current);
			case RESERVATION_CONFIRMED -> apply(ReservationProcessStatus.CONFIRMED);
			case RESERVATION_CANCELLED -> keep(Outcome.CONFLICT, current);
			case PROCESS_EXPIRED -> apply(ReservationProcessStatus.EXPIRED);
			case PROCESS_INVALID -> apply(ReservationProcessStatus.INVALID);
		};
	}

	private static boolean isIncoming(Signal signal) {
		return signal != Signal.PHONE_SUBMISSION_ACCEPTED && signal != Signal.PHONE_PUBLICATION_ACKNOWLEDGED;
	}

	private static ReservationProcessStatus terminalStatus(Signal signal) {
		return switch (signal) {
			case RESERVATION_CONFIRMED -> ReservationProcessStatus.CONFIRMED;
			case RESERVATION_CANCELLED -> ReservationProcessStatus.CANCELLED;
			case PROCESS_EXPIRED -> ReservationProcessStatus.EXPIRED;
			case PROCESS_INVALID -> ReservationProcessStatus.INVALID;
			default -> null;
		};
	}

	private static Decision apply(ReservationProcessStatus status) {
		return new Decision(Outcome.APPLY, status);
	}

	private static Decision keep(Outcome outcome, ReservationProcessStatus current) {
		return new Decision(outcome, current);
	}
}
