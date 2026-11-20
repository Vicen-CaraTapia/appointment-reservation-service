package ar.edu.um.prog2.appointmentreservation.reservation.domain.model;

import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import ar.edu.um.prog2.appointmentreservation.reservation.domain.model.AsynchronousReservationTransitions.Decision;
import ar.edu.um.prog2.appointmentreservation.reservation.domain.model.AsynchronousReservationTransitions.Outcome;
import ar.edu.um.prog2.appointmentreservation.reservation.domain.model.AsynchronousReservationTransitions.Signal;

import static ar.edu.um.prog2.appointmentreservation.reservation.domain.model.AsynchronousReservationTransitions.decide;
import static ar.edu.um.prog2.appointmentreservation.reservation.domain.model.ReservationProcessStatus.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AsynchronousReservationTransitionsTest {

	@Test
	void phoneRequestSubmissionAndBrokerAcknowledgmentAreDistinctFromFinalConfirmation() {
		assertThat(decide(WAITING_FOR_PHONE, Signal.PHONE_REQUESTED)).isEqualTo(new Decision(Outcome.APPLY, WAITING_FOR_PHONE));
		var accepted = decide(WAITING_FOR_PHONE, Signal.PHONE_SUBMISSION_ACCEPTED);
		assertThat(accepted).isEqualTo(new Decision(Outcome.APPLY, PHONE_SUBMISSION_PENDING));
		var published = decide(accepted.nextStatus(), Signal.PHONE_PUBLICATION_ACKNOWLEDGED);
		assertThat(published).isEqualTo(new Decision(Outcome.APPLY, WAITING_FOR_CONFIRMATION));
		assertThat(published.nextStatus().isTerminal()).isFalse();
		assertThat(decide(published.nextStatus(), Signal.RESERVATION_CONFIRMED))
				.isEqualTo(new Decision(Outcome.APPLY, CONFIRMED));
	}

	@ParameterizedTest
	@EnumSource(value = ReservationProcessStatus.class, names = {"PHONE_SUBMISSION_PENDING", "WAITING_FOR_CONFIRMATION"})
	void rejectionIsCorrectableEvenIfItArrivesBeforeTheBrokerAcknowledgmentIsRecorded(ReservationProcessStatus current) {
		var rejected = decide(current, Signal.PHONE_REJECTED);
		assertThat(rejected).isEqualTo(new Decision(Outcome.APPLY, WAITING_FOR_PHONE));
		assertThat(rejected.nextStatus().isTerminal()).isFalse();
		assertThat(decide(rejected.nextStatus(), Signal.PHONE_SUBMISSION_ACCEPTED))
				.isEqualTo(new Decision(Outcome.APPLY, PHONE_SUBMISSION_PENDING));
	}

	@ParameterizedTest
	@EnumSource(value = ReservationProcessStatus.class, names = {"WAITING_FOR_PHONE", "WAITING_FOR_CONFIRMATION"})
	void lateOrRepeatedPublicationAcknowledgmentDoesNotOverrideRejectionOrProgress(ReservationProcessStatus current) {
		assertThat(decide(current, Signal.PHONE_PUBLICATION_ACKNOWLEDGED)).isEqualTo(new Decision(Outcome.IGNORE, current));
	}

	@ParameterizedTest
	@MethodSource("earlyEvents")
	void earlyIncomingEvidenceRemainsPendingWithoutInventingAnInitialAcceptance(ReservationProcessStatus current, Signal signal) {
		assertThat(decide(current, signal)).isEqualTo(new Decision(Outcome.DEFER, current));
	}

	static Stream<Arguments> earlyEvents() {
		return Stream.of(PENDING_HOLD, HELD, CONFIRMATION_PENDING).flatMap(current ->
				Stream.of(Signal.PHONE_REQUESTED, Signal.PHONE_REJECTED, Signal.RESERVATION_CONFIRMED,
						Signal.PROCESS_EXPIRED, Signal.PROCESS_INVALID)
						.filter(signal -> current != CONFIRMATION_PENDING || signal != Signal.PHONE_REQUESTED)
						.map(signal -> Arguments.of(current, signal)));
	}

	@Test
	void aTrustedCorrelatedRequestCanResolveAnUncertainInitialRestAcceptance() {
		assertThat(decide(CONFIRMATION_PENDING, Signal.PHONE_REQUESTED))
				.isEqualTo(new Decision(Outcome.APPLY, WAITING_FOR_PHONE));
	}

	@ParameterizedTest
	@MethodSource("unsupportedPhoneOperations")
	void aNewSubmissionOrPublicationCannotBypassInitialAcceptanceOrAnotherPendingSubmission(
			ReservationProcessStatus current, Signal signal) {
		assertThat(decide(current, signal)).isEqualTo(new Decision(Outcome.CONFLICT, current));
	}

	static Stream<Arguments> unsupportedPhoneOperations() {
		return Stream.concat(Stream.of(PENDING_HOLD, HELD, CONFIRMATION_PENDING).flatMap(current ->
				Stream.of(Signal.PHONE_SUBMISSION_ACCEPTED, Signal.PHONE_PUBLICATION_ACKNOWLEDGED)
						.map(signal -> Arguments.of(current, signal))),
				Stream.of(PHONE_SUBMISSION_PENDING, WAITING_FOR_CONFIRMATION)
						.map(current -> Arguments.of(current, Signal.PHONE_SUBMISSION_ACCEPTED)));
	}

	@ParameterizedTest
	@EnumSource(value = ReservationProcessStatus.class, names = {"PHONE_SUBMISSION_PENDING", "WAITING_FOR_CONFIRMATION"})
	void aDifferentRequestCannotSilentlyReplaceTheRequestOfAnExistingSubmission(ReservationProcessStatus current) {
		assertThat(decide(current, Signal.PHONE_REQUESTED)).isEqualTo(new Decision(Outcome.CONFLICT, current));
	}

	@Test
	void aRejectionWithoutAnActiveSubmissionRequiresExplicitConflictHandling() {
		assertThat(decide(WAITING_FOR_PHONE, Signal.PHONE_REJECTED))
				.isEqualTo(new Decision(Outcome.CONFLICT, WAITING_FOR_PHONE));
	}

	@ParameterizedTest
	@MethodSource("authoritativeResults")
	void aResultCanArriveBeforeTheBrokerAcknowledgmentIsSaved(ReservationProcessStatus current, Signal signal,
			ReservationProcessStatus expected) {
		var result = decide(current, signal);
		assertThat(result).isEqualTo(new Decision(Outcome.APPLY, expected));
		assertThat(decide(result.nextStatus(), Signal.PHONE_PUBLICATION_ACKNOWLEDGED))
				.isEqualTo(new Decision(Outcome.IGNORE, expected));
	}

	static Stream<Arguments> authoritativeResults() {
		return Stream.of(WAITING_FOR_PHONE, PHONE_SUBMISSION_PENDING, WAITING_FOR_CONFIRMATION).flatMap(current ->
				Stream.of(Arguments.of(current, Signal.RESERVATION_CONFIRMED, CONFIRMED),
						Arguments.of(current, Signal.PROCESS_EXPIRED, EXPIRED),
						Arguments.of(current, Signal.PROCESS_INVALID, INVALID)));
	}

	@ParameterizedTest
	@MethodSource("terminalStatesAndAllSignals")
	void noSignalReopensOrOverwritesATerminalState(ReservationProcessStatus current, Signal signal) {
		var result = decide(current, signal);
		assertThat(result.nextStatus()).isEqualTo(current);
		assertThat(result.outcome()).isIn(Outcome.IGNORE, Outcome.CONFLICT);
		assertThat(current.isTerminal()).isTrue();
	}

	static Stream<Arguments> terminalStatesAndAllSignals() {
		return Stream.of(CONFIRMED, EXPIRED, INVALID).flatMap(current ->
				Stream.of(Signal.values()).map(signal -> Arguments.of(current, signal)));
	}

	@ParameterizedTest
	@MethodSource("contradictoryTerminalResults")
	void contradictoryFinalResultsAreConflictsRatherThanLastMessageWins(ReservationProcessStatus current, Signal signal) {
		assertThat(decide(current, signal)).isEqualTo(new Decision(Outcome.CONFLICT, current));
	}

	static Stream<Arguments> contradictoryTerminalResults() {
		return Stream.of(Arguments.of(CONFIRMED, Signal.PROCESS_EXPIRED), Arguments.of(CONFIRMED, Signal.PROCESS_INVALID),
				Arguments.of(EXPIRED, Signal.RESERVATION_CONFIRMED), Arguments.of(EXPIRED, Signal.PROCESS_INVALID),
				Arguments.of(INVALID, Signal.RESERVATION_CONFIRMED), Arguments.of(INVALID, Signal.PROCESS_EXPIRED));
	}

	@ParameterizedTest
	@EnumSource(value = ReservationProcessStatus.class, names = {"CONFIRMED", "EXPIRED", "INVALID"})
	void terminalProcessesRejectNewPhoneSubmissionIntents(ReservationProcessStatus current) {
		assertThat(decide(current, Signal.PHONE_SUBMISSION_ACCEPTED)).isEqualTo(new Decision(Outcome.CONFLICT, current));
	}

	@ParameterizedTest
	@EnumSource(value = ReservationProcessStatus.class, names = {"PENDING_HOLD", "HELD", "CONFIRMATION_PENDING",
			"WAITING_FOR_PHONE", "PHONE_SUBMISSION_PENDING", "WAITING_FOR_CONFIRMATION"})
	void nonterminalProgressMustNotBePresentedAsAFinalResult(ReservationProcessStatus current) {
		assertThat(current.isTerminal()).isFalse();
	}

	@Test
	void missingStateSignalOrDecisionFieldsCannotBecomeAnImplicitTransition() {
		assertThatThrownBy(() -> decide(null, Signal.PHONE_REQUESTED)).isInstanceOf(NullPointerException.class)
				.hasMessage("Current process status is required");
		assertThatThrownBy(() -> decide(WAITING_FOR_PHONE, null)).isInstanceOf(NullPointerException.class)
				.hasMessage("Process signal is required");
		assertThatThrownBy(() -> new Decision(null, WAITING_FOR_PHONE)).isInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> new Decision(Outcome.APPLY, null)).isInstanceOf(NullPointerException.class);
	}
}
