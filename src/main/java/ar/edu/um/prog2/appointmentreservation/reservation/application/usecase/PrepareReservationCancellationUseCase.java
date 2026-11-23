package ar.edu.um.prog2.appointmentreservation.reservation.application.usecase;

import java.time.Clock;
import java.util.Objects;
import ar.edu.um.prog2.appointmentreservation.reservation.application.model.PreparedReservationCancellation;
import ar.edu.um.prog2.appointmentreservation.reservation.application.port.out.CurrentReservationUser;
import ar.edu.um.prog2.appointmentreservation.reservation.application.port.out.ReservationProcessStore;

/** Commits the ownership-checked cancellation intent before any remote I/O. */
public final class PrepareReservationCancellationUseCase {
    private final CurrentReservationUser currentUser;
    private final ReservationProcessStore processes;
    private final Clock clock;

    public PrepareReservationCancellationUseCase(CurrentReservationUser currentUser,
            ReservationProcessStore processes, Clock clock) {
        this.currentUser = Objects.requireNonNull(currentUser);
        this.processes = Objects.requireNonNull(processes);
        this.clock = Objects.requireNonNull(clock);
    }

    public PreparedReservationCancellation prepare(long id, String reason) {
        var owner = currentUser.requireAuthenticated();
        if (id <= 0 || (reason != null && reason.length() > 500)) {
            throw new IllegalArgumentException("A positive process ID and reason of at most 500 characters are required");
        }
        return processes.prepareCancellation(id, owner, reason, clock.instant());
    }
}
