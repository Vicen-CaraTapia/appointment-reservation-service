package ar.edu.um.prog2.appointmentreservation.reservation.application.usecase;

import java.time.*;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import ar.edu.um.prog2.appointmentreservation.reservation.application.exception.*;
import ar.edu.um.prog2.appointmentreservation.reservation.application.model.*;
import ar.edu.um.prog2.appointmentreservation.reservation.application.model.ReservationProcessProgress.RequiredAction;
import ar.edu.um.prog2.appointmentreservation.reservation.application.port.out.*;
import ar.edu.um.prog2.appointmentreservation.reservation.domain.model.ReservationProcessStatus;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class GetReservationProcessProgressUseCaseTest {
	private static final Instant NOW = Instant.parse("2030-01-01T12:00:00Z");
	private static final AuthenticatedReservationUser OWNER = new AuthenticatedReservationUser(42, "alice");
	private final CurrentReservationUser currentUser = mock(CurrentReservationUser.class);
	private final ReservationProcessStore store = mock(ReservationProcessStore.class);
	private final GetReservationProcessProgressUseCase useCase = new GetReservationProcessProgressUseCase(currentUser, store, Clock.fixed(NOW, ZoneOffset.UTC));

	@ParameterizedTest
	@EnumSource(ReservationProcessStatus.class)
	void onlyWaitingForPhoneWithAnAppliedRequestAndFutureDeadlineEnablesInput(ReservationProcessStatus status) {
		observe(status, NOW.plusSeconds(30), true, null);
		assertThat(useCase.get(9).requiredAction()).isEqualTo(status == ReservationProcessStatus.WAITING_FOR_PHONE
				? RequiredAction.SUBMIT_PHONE : RequiredAction.NONE);
		verify(store).findOwnedProgress(9, OWNER);
		verifyNoMoreInteractions(store);
	}
	@Test
	void correctionIsExposedUsingControlledReasonRatherThanMessageText() {
		observe(ReservationProcessStatus.WAITING_FOR_PHONE, NOW.plusSeconds(30), true, "INVALID_PHONE_NUMBER");
		var result = useCase.get(9);
		assertThat(result.requiredAction()).isEqualTo(RequiredAction.CORRECT_PHONE);
		assertThat(result.reason()).isEqualTo(ReservationProcessProgress.Reason.INVALID_PHONE_NUMBER);
	}
	@Test
	void acceptanceBeforeTheKafkaRequestDoesNotEnableInput() {
		observe(ReservationProcessStatus.WAITING_FOR_PHONE, NOW.plusSeconds(30), false, null);
		assertThat(useCase.get(9).requiredAction()).isEqualTo(RequiredAction.NONE);
	}
	@ParameterizedTest
	@ValueSource(ints = {-1, 0})
	void elapsedDeadlineDoesNotMutateOrInventAnAuthoritativeExpiredState(int seconds) {
		observe(ReservationProcessStatus.WAITING_FOR_PHONE, NOW.plusSeconds(seconds), true, null);
		var result = useCase.get(9);
		assertThat(result.requiredAction()).isEqualTo(RequiredAction.NONE);
		assertThat(result.status()).isEqualTo(ReservationProcessStatus.WAITING_FOR_PHONE);
		verify(store).findOwnedProgress(9, OWNER);
		verifyNoMoreInteractions(store);
	}
	@Test
	void nullablePendingDeadlineAndUnknownReasonsAreHandledWithoutLeakingDiagnostics() {
		observe(ReservationProcessStatus.PENDING_HOLD, null, false, null);
		assertThat(useCase.get(9).expiresAt()).isNull();
		observe(ReservationProcessStatus.WAITING_FOR_PHONE, NOW.plusSeconds(30), true, "PRIVATE_DIAGNOSTIC");
		assertThat(useCase.get(9).reason()).isNull();
		assertThat(useCase.get(9).requiredAction()).isEqualTo(RequiredAction.NONE);
	}
	@Test
	void missingOrForeignOwnedIdsAreReportedAsNotFound() {
		when(currentUser.requireAuthenticated()).thenReturn(OWNER);
		when(store.findOwnedProgress(9, OWNER)).thenReturn(Optional.empty());
		assertThatThrownBy(() -> useCase.get(9)).isInstanceOfSatisfying(ReservationProcessStateException.class,
				failure -> assertThat(failure.reason()).isEqualTo(ReservationProcessStateException.Reason.NOT_FOUND));
	}
	@Test
	void requiresAuthenticationBeforeAnyRead() {
		when(currentUser.requireAuthenticated()).thenThrow(new ReservationAuthenticationRequiredException());
		assertThatThrownBy(() -> useCase.get(9)).isInstanceOf(ReservationAuthenticationRequiredException.class);
		verifyNoInteractions(store);
	}
	@ParameterizedTest
	@ValueSource(longs = {0, -1})
	void rejectsInvalidLocalIds(long id) {
		when(currentUser.requireAuthenticated()).thenReturn(OWNER);
		assertThatThrownBy(() -> useCase.get(id)).isInstanceOf(IllegalArgumentException.class);
		verifyNoInteractions(store);
	}
	private void observe(ReservationProcessStatus status, Instant expires, boolean ready, String reason) {
		when(currentUser.requireAuthenticated()).thenReturn(OWNER);
		when(store.findOwnedProgress(9, OWNER)).thenReturn(Optional.of(new StoredReservationProgress(9, status, expires, ready, reason)));
	}
}
