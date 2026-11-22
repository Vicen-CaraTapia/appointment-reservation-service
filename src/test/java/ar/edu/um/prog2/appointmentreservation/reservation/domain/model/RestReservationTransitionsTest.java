package ar.edu.um.prog2.appointmentreservation.reservation.domain.model;

import java.util.Arrays;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.EnumSource;
import ar.edu.um.prog2.appointmentreservation.reservation.domain.model.RestReservationTransitions.Observation;
import ar.edu.um.prog2.appointmentreservation.reservation.domain.model.AsynchronousReservationTransitions.Outcome;
import static org.assertj.core.api.Assertions.assertThat;

class RestReservationTransitionsTest {
	@ParameterizedTest @MethodSource("nonterminalObservations")
	void onlyFinalEvidenceAdvancesANonterminalWorkflow(ReservationProcessStatus current, Observation observation) {
		var decision = RestReservationTransitions.decide(current, observation);
		if (observation == Observation.PHONE_PENDING) {
			assertThat(decision.outcome()).isEqualTo(Outcome.IGNORE);
			assertThat(decision.nextStatus()).isEqualTo(current);
		} else {
			assertThat(decision.outcome()).isEqualTo(Outcome.APPLY);
			assertThat(decision.nextStatus().name()).isEqualTo(observation.name());
		}
	}
	static Stream<Arguments> nonterminalObservations() {
		return Arrays.stream(ReservationProcessStatus.values()).filter(state -> !state.isTerminal())
				.flatMap(state -> Arrays.stream(Observation.values()).map(observation -> Arguments.of(state, observation)));
	}

	@ParameterizedTest @MethodSource("terminalObservations")
	void terminalsStayFinalAndOnlyConfirmedCanAdvanceToCancelled(ReservationProcessStatus current,Observation observation) {
		var decision = RestReservationTransitions.decide(current,observation);
		boolean cancellation = current == ReservationProcessStatus.CONFIRMED && observation == Observation.CANCELLED;
		boolean compatible = current.name().equals(observation.name()) || (observation == Observation.FAILED
				&& (current == ReservationProcessStatus.EXPIRED || current == ReservationProcessStatus.INVALID));
		assertThat(decision.outcome()).isEqualTo(cancellation ? Outcome.APPLY : compatible ? Outcome.IGNORE : Outcome.CONFLICT);
		assertThat(decision.nextStatus()).isEqualTo(cancellation ? ReservationProcessStatus.CANCELLED : current);
	}
	static Stream<Arguments> terminalObservations() {
		return Arrays.stream(ReservationProcessStatus.values()).filter(ReservationProcessStatus::isTerminal)
				.flatMap(state -> Arrays.stream(Observation.values()).map(observation -> Arguments.of(state,observation)));
	}

	@ParameterizedTest @EnumSource(value=ReservationProcessStatus.class,names={"CANCELLED","FAILED"})
	void lateKafkaEvidenceCannotReopenRestFinalStates(ReservationProcessStatus current) {
		for (var signal : AsynchronousReservationTransitions.Signal.values()) {
			assertThat(AsynchronousReservationTransitions.decide(current,signal).nextStatus()).isEqualTo(current);
			assertThat(AsynchronousReservationTransitions.decide(current,signal).outcome()).isNotEqualTo(Outcome.APPLY);
		}
	}
}
