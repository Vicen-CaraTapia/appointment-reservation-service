package ar.edu.um.prog2.appointmentreservation.reservation.application.usecase;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import ar.edu.um.prog2.appointmentreservation.reservation.application.model.*;
import ar.edu.um.prog2.appointmentreservation.reservation.application.port.out.*;
import ar.edu.um.prog2.appointmentreservation.reservation.application.exception.PhoneSubmissionStateException;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class SubmitPhoneInformationUseCaseTest {
	private static final Instant NOW = Instant.parse("2030-01-01T12:00:00Z");
	private final PreparePhoneSubmissionUseCase prepare = mock(PreparePhoneSubmissionUseCase.class);
	private final PhoneSubmissionStore store = mock(PhoneSubmissionStore.class);
	private final PhoneSubmissionPublisher publisher = mock(PhoneSubmissionPublisher.class);
	private final PhoneSubmissionIntent intent = new PhoneSubmissionIntent("send", 9, "request", "process", "hold", "group",
			"1234567", NOW, PhoneSubmissionIntent.State.PENDING);
	private final PhonePublicationClaim claim = new PhonePublicationClaim(UUID.randomUUID(), intent);
	private final SubmitPhoneInformationUseCase useCase = new SubmitPhoneInformationUseCase(prepare, store, publisher,
			Clock.fixed(NOW, ZoneOffset.UTC), 3, Duration.ofMinutes(3), Duration.ofMillis(10), Duration.ofSeconds(5));

	@Test
	void preparesCommitsClaimsThenPublishesAndRecordsOnlyAfterAcknowledgment() {
		when(prepare.prepare(9, "1234567")).thenReturn(intent);
		claimable();
		var broker = new CompletableFuture<Void>();
		when(publisher.publish(intent)).thenAnswer(call -> {
			verify(store, never()).completePublication(any(), any(), any());
			broker.complete(null);
			return broker;
		});
		assertThat(useCase.submit(9, "1234567")).isSameAs(intent);
		var order = inOrder(prepare, store, publisher);
		order.verify(prepare).prepare(9, "1234567");
		order.verify(store).claimPublication("send", NOW, Duration.ofMinutes(3), 3);
		order.verify(publisher).publish(intent);
		order.verify(store).completePublication("send", claim.token(), NOW);
		verify(store, never()).deferPublication(any(), any(), any());
	}

	@ParameterizedTest
	@ValueSource(booleans = {true, false})
	void timeoutOrBrokerFailureDefersTheSameIdentityWithoutRecordingPublication(boolean timeout) {
		when(prepare.prepare(9, "1234567")).thenReturn(intent);
		claimable();
		when(publisher.publish(intent)).thenReturn(timeout ? new CompletableFuture<>()
				: CompletableFuture.failedFuture(new IllegalStateException("private diagnostic")));
		assertThat(useCase.submit(9, "1234567")).isSameAs(intent);
		verify(store).deferPublication("send", claim.token(), NOW.plusSeconds(5));
		verify(store, never()).completePublication(any(), any(), any());
		verify(publisher, times(1)).publish(intent);
	}

	@Test
	void unavailableOrAlreadyClaimedIntentDoesNotPublish() {
		when(prepare.prepare(9, "1234567")).thenReturn(intent);
		when(store.claimPublication(any(), any(), any(), anyInt())).thenReturn(Optional.empty());
		useCase.submit(9, "1234567");
		verifyNoInteractions(publisher);
	}

	@Test
	void localCommitFailureAfterAcknowledgmentLeavesTheClaimForRecovery() {
		when(prepare.prepare(9, "1234567")).thenReturn(intent);
		claimable();
		when(publisher.publish(intent)).thenReturn(CompletableFuture.completedFuture(null));
		doThrow(new PhoneSubmissionStateException(PhoneSubmissionStateException.Reason.STORAGE_FAILURE))
				.when(store).completePublication(any(), any(), any());
		assertThatThrownBy(() -> useCase.submit(9, "1234567")).isInstanceOf(PhoneSubmissionStateException.class);
		verify(store, never()).deferPublication(any(), any(), any());
	}

	@Test
	void recoveryIsABoundedPassAndContinuesAfterAnIndividualFailure() {
		when(store.findReady(NOW, 10, 3)).thenReturn(List.of("failed", "send"));
		when(store.claimPublication(eq("failed"), any(), any(), anyInt())).thenThrow(new IllegalStateException());
		claimable();
		when(publisher.publish(intent)).thenReturn(CompletableFuture.completedFuture(null));
		assertThat(useCase.recover(10)).isEqualTo(new SubmitPhoneInformationUseCase.RecoveryResult(2, 1, 1));
		verify(store, times(1)).findReady(NOW, 10, 3);
		verifyNoInteractions(prepare);
	}

	@Test
	void validationOrOwnershipFailureCannotReachThePublisher() {
		when(prepare.prepare(9, "1234567")).thenThrow(new PhoneSubmissionStateException(PhoneSubmissionStateException.Reason.NOT_FOUND));
		assertThatThrownBy(() -> useCase.submit(9, "1234567")).isInstanceOf(PhoneSubmissionStateException.class);
		verifyNoInteractions(store, publisher);
	}

	private void claimable() { when(store.claimPublication("send", NOW, Duration.ofMinutes(3), 3)).thenReturn(Optional.of(claim)); }
}
