package ar.edu.um.prog2.appointmentreservation.reservation.application.usecase;

import java.time.*;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import ar.edu.um.prog2.appointmentreservation.reservation.application.exception.*;
import ar.edu.um.prog2.appointmentreservation.reservation.application.model.*;
import ar.edu.um.prog2.appointmentreservation.reservation.application.port.out.*;
import ar.edu.um.prog2.appointmentreservation.reservation.domain.model.ReservationProcessStatus;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class CancelOwnedReservationUseCaseTest {
    private final Instant now = Instant.parse("2026-11-23T12:00:00Z");
    private final AuthenticatedReservationUser owner = new AuthenticatedReservationUser(42, "alice");
    private final CurrentReservationUser users = mock(CurrentReservationUser.class);
    private final ReservationProcessStore store = mock(ReservationProcessStore.class);
    private final ReservationCanceller remote = mock(ReservationCanceller.class);
    private final ListOwnedReservationsUseCase listing = mock(ListOwnedReservationsUseCase.class);
    private final CancelOwnedReservationUseCase useCase = new CancelOwnedReservationUseCase(users, store, remote, listing,
            Clock.fixed(now, ZoneOffset.UTC));

    private void prepare(boolean fresh) {
        when(users.requireAuthenticated()).thenReturn(owner);
        when(store.prepareCancellation(123, owner, null, now)).thenReturn(new PreparedReservationCancellation(
                123, 8251, "trusted-process", ReservationProcessStatus.CANCELLATION_PENDING, null, now, fresh));
    }
    private StoredReservationProcess observation(ReservationProcessStatus status) {
        return new StoredReservationProcess(123, 3, status,
                new StartReservationCommand(202, LocalDate.of(2026,12,1), LocalTime.NOON), "user-42",
                new AppointmentHold("hold", "trusted-process", now.plusSeconds(300)), null, null);
    }

    @Test void commitsIntentBeforeOnePostAndRecordsCorrelatedResult() {
        prepare(true);
        var result = new ReservationCancellation(8251,"trusted-process",now);
        when(remote.cancel("trusted-process",null)).thenReturn(result);
        when(store.recordCancellation(123,owner,result)).thenReturn(observation(ReservationProcessStatus.CANCELLED));
        assertThat(useCase.cancel(123,null).status()).isEqualTo(ReservationProcessStatus.CANCELLED);
        var order = inOrder(store,remote);
        order.verify(store).prepareCancellation(123,owner,null,now);
        order.verify(remote).cancel("trusted-process",null);
        order.verify(store).recordCancellation(123,owner,result);
        verifyNoInteractions(listing);
    }

    @ParameterizedTest @EnumSource(value=ReservationCancellationException.Reason.class,
            names={"TIMEOUT","UNAVAILABLE","REMOTE_FAILURE","INVALID_RESPONSE"})
    void ambiguousOutcomesReconcileOnceWithoutResending(ReservationCancellationException.Reason reason) {
        prepare(true);
        when(remote.cancel(anyString(),isNull())).thenThrow(new ReservationCancellationException(reason));
        when(store.findOwned(123,owner)).thenReturn(Optional.of(observation(ReservationProcessStatus.CANCELLATION_PENDING)),
                Optional.of(observation(ReservationProcessStatus.CANCELLED)));
        assertThat(useCase.cancel(123,null).status()).isEqualTo(ReservationProcessStatus.CANCELLED);
        verify(remote).cancel("trusted-process",null);
        verify(listing).list(new OwnedReservationQuery(null, LocalDate.of(2026,12,1),LocalDate.of(2026,12,1),0,1));
    }

    @Test void repeatedPendingIntentOnlyQueriesAndKeepsUnknownWhenNoEvidenceExists() {
        prepare(false);
        when(store.findOwned(123,owner)).thenReturn(Optional.of(observation(ReservationProcessStatus.CANCELLATION_PENDING)));
        assertThatThrownBy(() -> useCase.cancel(123,null)).isInstanceOfSatisfying(ReservationCancellationException.class,
                error -> assertThat(error.reason()).isEqualTo(ReservationCancellationException.Reason.OUTCOME_UNKNOWN));
        verifyNoInteractions(remote);
        verify(listing).list(any());
    }

    @Test void aKafkaResultAlreadyPersistedConvergesWithoutAnotherPost() {
        prepare(false);
        when(store.findOwned(123,owner)).thenReturn(Optional.of(observation(ReservationProcessStatus.CANCELLED)));
        assertThat(useCase.cancel(123,null).status()).isEqualTo(ReservationProcessStatus.CANCELLED);
        verifyNoInteractions(remote,listing);
    }

    @Test void alreadyCancelledDoesNotContactAnyDependency() {
        prepare(false);
        when(store.prepareCancellation(123,owner,null,now)).thenReturn(new PreparedReservationCancellation(
                123,8251,"trusted-process",ReservationProcessStatus.CANCELLED,null,now,false));
        when(store.findOwned(123,owner)).thenReturn(Optional.of(observation(ReservationProcessStatus.CANCELLED)));
        assertThat(useCase.cancel(123,null).status()).isEqualTo(ReservationProcessStatus.CANCELLED);
        verifyNoInteractions(remote,listing);
    }

    @Test void mismatchedResponseNeverBecomesPersistedCancellation() {
        prepare(true);
        when(remote.cancel(anyString(),isNull())).thenReturn(new ReservationCancellation(999,"foreign-process",now));
        when(store.findOwned(123,owner)).thenReturn(Optional.of(observation(ReservationProcessStatus.CANCELLATION_PENDING)));
        assertThatThrownBy(() -> useCase.cancel(123,null)).isInstanceOfSatisfying(ReservationCancellationException.class,
                error -> assertThat(error.reason()).isEqualTo(ReservationCancellationException.Reason.OUTCOME_UNKNOWN));
        verify(store,never()).recordCancellation(anyLong(),any(),any());
    }

    @Test void failedReconciliationReturnsControlledUncertaintyWithoutRawCauses() {
        prepare(false);
        when(store.findOwned(123,owner)).thenReturn(Optional.of(observation(ReservationProcessStatus.CANCELLATION_PENDING)));
        when(listing.list(any())).thenThrow(new IllegalStateException("private transport diagnostic"));
        assertThatThrownBy(() -> useCase.cancel(123,null)).isInstanceOfSatisfying(ReservationCancellationException.class,
                error -> assertThat(error.reason()).isEqualTo(ReservationCancellationException.Reason.OUTCOME_UNKNOWN))
                .hasNoCause().hasMessageNotContaining("private transport diagnostic");
        verifyNoInteractions(remote);
    }

    @Test void remoteSuccessAndLocalFailureUseReconciliation() {
        prepare(true);
        when(remote.cancel(anyString(),isNull())).thenReturn(new ReservationCancellation(8251,"trusted-process",now));
        when(store.recordCancellation(anyLong(),any(),any())).thenThrow(new ReservationProcessStateException(
                ReservationProcessStateException.Reason.STORAGE_FAILURE));
        when(store.findOwned(123,owner)).thenReturn(Optional.of(observation(ReservationProcessStatus.CANCELLED)));
        assertThat(useCase.cancel(123,null).status()).isEqualTo(ReservationProcessStatus.CANCELLED);
        verify(remote).cancel("trusted-process",null);
    }

    @Test void missingOrForeignOwnerNeverCallsRemote() {
        prepare(true);
        when(store.prepareCancellation(anyLong(),any(),isNull(),any())).thenThrow(new ReservationProcessStateException(
                ReservationProcessStateException.Reason.NOT_FOUND));
        assertThatThrownBy(() -> useCase.cancel(123,null)).isInstanceOf(ReservationProcessStateException.class);
        verifyNoInteractions(remote,listing);
    }

    @ParameterizedTest @EnumSource(value=ReservationCancellationException.Reason.class,
            names={"RESERVATION_NOT_FOUND","RESERVATION_OWNER_MISMATCH","RESERVATION_NOT_CANCELLABLE","UNAUTHORIZED","FORBIDDEN"})
    void technicalAndBusinessRejectionsRetainTheirMeaning(ReservationCancellationException.Reason reason) {
        prepare(true);
        when(remote.cancel(anyString(),isNull())).thenThrow(new ReservationCancellationException(reason));
        assertThatThrownBy(() -> useCase.cancel(123,null)).isInstanceOfSatisfying(ReservationCancellationException.class,
                error -> assertThat(error.reason()).isEqualTo(reason));
        verifyNoInteractions(listing);
    }
}
