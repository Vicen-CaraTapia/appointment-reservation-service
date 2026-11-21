package ar.edu.um.prog2.appointmentreservation.reservation.application.usecase;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import ar.edu.um.prog2.appointmentreservation.reservation.application.exception.ReservationEventStorageException;
import ar.edu.um.prog2.appointmentreservation.reservation.application.model.ReservationActionEvent;
import ar.edu.um.prog2.appointmentreservation.reservation.application.model.ReservationEventReceipt;
import ar.edu.um.prog2.appointmentreservation.reservation.application.model.ReservationEventReceipt.State;
import ar.edu.um.prog2.appointmentreservation.reservation.application.port.out.ReservationEventStore;
import ar.edu.um.prog2.appointmentreservation.reservation.domain.model.AsynchronousReservationTransitions.Signal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class ProcessReservationActionUseCaseTest {
	private static final Instant TIME = Instant.parse("2030-01-03T12:00:00Z");
	private static final ReservationActionEvent REQUEST = new ReservationActionEvent("request", Signal.PHONE_REQUESTED,
			"process", "hold", null, TIME, TIME.plusSeconds(300), null, null, null);
	private final ReservationEventStore events = mock(ReservationEventStore.class);
	private final ProcessReservationActionUseCase useCase = new ProcessReservationActionUseCase(events);

	@Test
	void receivesBeforeApplyingAndContinuesAnEarlierEventAfterNewEvidence() {
		var applied = receipt("request", State.APPLIED, false);
		when(events.receive(REQUEST)).thenReturn(receipt("request", State.PENDING, false));
		when(events.applyPending("request")).thenReturn(applied);
		when(events.findPending("process", 100)).thenReturn(List.of("earlier-result"));
		when(events.applyPending("earlier-result")).thenReturn(receipt("earlier-result", State.APPLIED, false));
		assertThat(useCase.process(REQUEST)).isEqualTo(applied);
		var order = inOrder(events);
		order.verify(events).receive(REQUEST);
		order.verify(events).applyPending("request");
		order.verify(events).findPending("process", 100);
		order.verify(events).applyPending("earlier-result");
		verifyNoMoreInteractions(events);
	}

	@ParameterizedTest
	@EnumSource(value = State.class, names = {"PENDING", "IGNORED", "CONFLICT"})
	void anInapplicableOrConflictingEffectDoesNotTriggerRepeatedAttempts(State state) {
		when(events.receive(REQUEST)).thenReturn(receipt("request", State.PENDING, false));
		var result = receipt("request", state, false);
		when(events.applyPending("request")).thenReturn(result);
		assertThat(useCase.process(REQUEST)).isEqualTo(result);
		var order = inOrder(events);
		order.verify(events).receive(REQUEST);
		order.verify(events).applyPending("request");
		verifyNoMoreInteractions(events);
	}

	@ParameterizedTest
	@EnumSource(State.class)
	void incompatibleRedeliveriesPreserveTheOriginalReceiptWithoutApplyingContent(State state) {
		var conflict = receipt("request", state, true);
		when(events.receive(REQUEST)).thenReturn(conflict);
		assertThat(useCase.process(REQUEST)).isEqualTo(conflict);
		inOrder(events).verify(events).receive(REQUEST);
		verifyNoMoreInteractions(events);
	}

	@ParameterizedTest
	@EnumSource(value = State.class, names = {"IGNORED", "CONFLICT"})
	void previouslyHandledNonAppliedReceiptsAreNotReapplied(State state) {
		var handled = receipt("request", state, false);
		when(events.receive(REQUEST)).thenReturn(handled);
		assertThat(useCase.process(REQUEST)).isEqualTo(handled);
		inOrder(events).verify(events).receive(REQUEST);
		verifyNoMoreInteractions(events);
	}

	@Test
	void replayOfAnAppliedEventResumesInterruptedWorkWithoutApplyingItAgain() {
		var applied = receipt("request", State.APPLIED, false);
		when(events.receive(REQUEST)).thenReturn(applied);
		when(events.findPending("process", 100)).thenReturn(List.of("pending"));
		when(events.applyPending("pending")).thenReturn(receipt("pending", State.PENDING, false));
		assertThat(useCase.process(REQUEST)).isEqualTo(applied);
		var order = inOrder(events);
		order.verify(events).receive(REQUEST);
		order.verify(events).findPending("process", 100);
		order.verify(events).applyPending("pending");
		verifyNoMoreInteractions(events);
	}

	@Test
	void aFailedReceiptCommitPreventsApplication() {
		var failure = new ReservationEventStorageException();
		when(events.receive(REQUEST)).thenThrow(failure);
		assertThatThrownBy(() -> useCase.process(REQUEST)).isSameAs(failure);
		inOrder(events).verify(events).receive(REQUEST);
		verifyNoMoreInteractions(events);
	}

	@Test
	void aFailedEffectCommitIsPropagatedWithoutPretendingApplicationSucceeded() {
		when(events.receive(REQUEST)).thenReturn(receipt("request", State.PENDING, false));
		var failure = new ReservationEventStorageException();
		when(events.applyPending("request")).thenThrow(failure);
		assertThatThrownBy(() -> useCase.process(REQUEST)).isSameAs(failure);
		var order = inOrder(events);
		order.verify(events).receive(REQUEST);
		order.verify(events).applyPending("request");
		verifyNoMoreInteractions(events);
	}

	@Test
	void continuationFailureStopsTheBatchAndPropagatesWithoutAnInternalRetryLoop() {
		when(events.findPending("process", 2)).thenReturn(List.of("first", "second"));
		var failure = new ReservationEventStorageException();
		when(events.applyPending("first")).thenThrow(failure);
		assertThatThrownBy(() -> useCase.resumePending("process", 2)).isSameAs(failure);
		var order = inOrder(events);
		order.verify(events).findPending("process", 2);
		order.verify(events).applyPending("first");
		verifyNoMoreInteractions(events);
	}

	@Test
	void continuationReturnsDurableResultsInOneBoundedPassEvenWhenSomeRemainPending() {
		var deferred = receipt("first", State.PENDING, false);
		var applied = receipt("second", State.APPLIED, false);
		when(events.findPending("process", 2)).thenReturn(List.of("first", "second"));
		when(events.applyPending("first")).thenReturn(deferred);
		when(events.applyPending("second")).thenReturn(applied);
		assertThat(useCase.resumePending("process", 2)).containsExactly(deferred, applied);
		var order = inOrder(events);
		order.verify(events).findPending("process", 2);
		order.verify(events).applyPending("first");
		order.verify(events).applyPending("second");
		verifyNoMoreInteractions(events);
	}

	@ParameterizedTest
	@ValueSource(ints = {-1, 0, 101})
	void rejectsUnboundedContinuationBeforeStorage(int limit) {
		assertThatThrownBy(() -> useCase.resumePending("process", limit)).isInstanceOf(IllegalArgumentException.class);
		verifyNoInteractions(events);
	}

	@ParameterizedTest
	@NullSource
	@ValueSource(strings = {"", " "})
	void continuationRequiresAnExternalProcessIdentifier(String processId) {
		assertThatThrownBy(() -> useCase.resumePending(processId, 1)).isInstanceOf(IllegalArgumentException.class);
		verifyNoInteractions(events);
	}

	@Test
	void requiresValidatedEvidenceBeforeAnyStorageOperation() {
		assertThatThrownBy(() -> useCase.process(null)).isInstanceOf(NullPointerException.class);
		verifyNoInteractions(events);
	}

	private static ReservationEventReceipt receipt(String id, State state, boolean conflict) {
		return new ReservationEventReceipt(id, state, state == State.PENDING ? null : 42L, conflict);
	}
}
