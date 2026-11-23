package ar.edu.um.prog2.appointmentreservation.reservation.application.usecase;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import ar.edu.um.prog2.appointmentreservation.reservation.application.model.AuthenticatedReservationUser;
import ar.edu.um.prog2.appointmentreservation.reservation.application.port.out.CurrentReservationUser;
import ar.edu.um.prog2.appointmentreservation.reservation.application.port.out.ReservationProcessStore;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PrepareReservationCancellationUseCaseTest {
    private final CurrentReservationUser users = mock(CurrentReservationUser.class);
    private final ReservationProcessStore store = mock(ReservationProcessStore.class);
    private final Instant now = Instant.parse("2026-11-23T12:00:00Z");
    private final PrepareReservationCancellationUseCase useCase = new PrepareReservationCancellationUseCase(
            users, store, Clock.fixed(now, ZoneOffset.UTC));

    @Test void derivesOwnerAndTimeFromTrustedBoundaries() {
        var owner = new AuthenticatedReservationUser(42, "alice");
        when(users.requireAuthenticated()).thenReturn(owner);
        useCase.prepare(123, null);
        verify(store).prepareCancellation(123, owner, null, now);
    }

    @Test void invalidInputsNeverReachPersistence() {
        when(users.requireAuthenticated()).thenReturn(new AuthenticatedReservationUser(42, "alice"));
        assertThatThrownBy(() -> useCase.prepare(0, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> useCase.prepare(123, "x".repeat(501))).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(store);
    }

    @Test void authenticationFailureNeverReachesPersistence() {
        when(users.requireAuthenticated()).thenThrow(new IllegalStateException("authentication required"));
        assertThatThrownBy(() -> useCase.prepare(123, null)).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(store);
    }
}
