package ar.edu.um.prog2.appointmentreservation.reservation.domain.model;

import java.util.Objects;
import ar.edu.um.prog2.appointmentreservation.reservation.domain.model.AsynchronousReservationTransitions.Decision;
import ar.edu.um.prog2.appointmentreservation.reservation.domain.model.AsynchronousReservationTransitions.Outcome;

/** Correlated persisted reservation evidence, not synthetic Kafka events or phone workflow instructions. */
public final class RestReservationTransitions {
	public enum Observation { PHONE_PENDING, CONFIRMED, CANCELLED, FAILED }
	private RestReservationTransitions() { }

	public static Decision decide(ReservationProcessStatus current, Observation observation) {
		Objects.requireNonNull(current, "Current process state is required");
		Objects.requireNonNull(observation, "Verified reservation state is required");
		if (current == ReservationProcessStatus.CANCELLATION_PENDING) {
			return new Decision(observation == Observation.CANCELLED ? Outcome.APPLY : Outcome.IGNORE,
					observation == Observation.CANCELLED ? ReservationProcessStatus.CANCELLED : current);
		}
		if (observation == Observation.PHONE_PENDING) {
			return new Decision(current.isTerminal() ? Outcome.CONFLICT : Outcome.IGNORE, current);
		}
		var target = switch (observation) {
			case CONFIRMED -> ReservationProcessStatus.CONFIRMED;
			case CANCELLED -> ReservationProcessStatus.CANCELLED;
			case FAILED -> ReservationProcessStatus.FAILED;
			case PHONE_PENDING -> throw new IllegalStateException("Pending evidence has no terminal target");
		};
		if (current == target || (observation == Observation.FAILED
				&& (current == ReservationProcessStatus.EXPIRED || current == ReservationProcessStatus.INVALID))) {
			return new Decision(Outcome.IGNORE, current);
		}
		if (current.isTerminal() && !(current == ReservationProcessStatus.CONFIRMED && target == ReservationProcessStatus.CANCELLED)) {
			return new Decision(Outcome.CONFLICT, current);
		}
		return new Decision(Outcome.APPLY, target);
	}
}
