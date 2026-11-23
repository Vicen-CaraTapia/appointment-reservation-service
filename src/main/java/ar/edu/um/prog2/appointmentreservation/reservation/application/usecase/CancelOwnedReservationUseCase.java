package ar.edu.um.prog2.appointmentreservation.reservation.application.usecase;

import java.time.Clock;
import java.util.Objects;
import ar.edu.um.prog2.appointmentreservation.reservation.application.exception.ReservationCancellationException;
import ar.edu.um.prog2.appointmentreservation.reservation.application.exception.ReservationProcessStateException;
import ar.edu.um.prog2.appointmentreservation.reservation.application.model.*;
import ar.edu.um.prog2.appointmentreservation.reservation.application.port.out.*;
import ar.edu.um.prog2.appointmentreservation.reservation.domain.model.ReservationProcessStatus;

/** One remote POST after a durable claim; ambiguous outcomes get one bounded reconciliation, never a POST retry. */
public final class CancelOwnedReservationUseCase {
    private final CurrentReservationUser users;
    private final ReservationProcessStore processes;
    private final ReservationCanceller remote;
    private final ListOwnedReservationsUseCase listing;
    private final Clock clock;

    public CancelOwnedReservationUseCase(CurrentReservationUser users, ReservationProcessStore processes,
            ReservationCanceller remote, ListOwnedReservationsUseCase listing, Clock clock) {
        this.users = Objects.requireNonNull(users);
        this.processes = Objects.requireNonNull(processes);
        this.remote = Objects.requireNonNull(remote);
        this.listing = Objects.requireNonNull(listing);
        this.clock = Objects.requireNonNull(clock);
    }

    public StoredReservationProcess cancel(long id, String reason) {
        var owner = users.requireAuthenticated();
        if (id <= 0 || (reason != null && reason.length() > 500)) {
            throw new IllegalArgumentException("A positive process ID and reason of at most 500 characters are required");
        }
        var intent = processes.prepareCancellation(id, owner, reason, clock.instant());
        if (intent.status() == ReservationProcessStatus.CANCELLED) { return load(id, owner); }
        if (!intent.newlyClaimed()) { return reconcile(id, owner); }
        try {
            var result = remote.cancel(intent.externalProcessId(), intent.reason());
            if (result == null || result.reservationId() != intent.reservationId()
                    || !intent.externalProcessId().equals(result.reservationProcessId()) || result.cancelledAt() == null) {
                throw new ReservationCancellationException(ReservationCancellationException.Reason.INVALID_RESPONSE);
            }
            return processes.recordCancellation(id, owner, result);
        } catch (ReservationCancellationException failure) {
            switch (failure.reason()) {
                case TIMEOUT, UNAVAILABLE, REMOTE_FAILURE, INVALID_RESPONSE -> { return reconcile(id, owner); }
                default -> throw failure;
            }
        } catch (ReservationProcessStateException failure) {
            return reconcile(id, owner);
        }
    }

    private StoredReservationProcess reconcile(long id, AuthenticatedReservationUser owner) {
        try {
            var before = load(id, owner);
            if (before.status() == ReservationProcessStatus.CANCELLED) { return before; }
            // A9.2 reconciles every authorized row before pagination, even with size=1.
            listing.list(new OwnedReservationQuery(null, before.selection().date(), before.selection().date(), 0, 1));
            var after = load(id, owner);
            if (after.status() == ReservationProcessStatus.CANCELLED) { return after; }
        } catch (RuntimeException failure) {
            // Keep durable uncertainty and discard transport/persistence diagnostics.
        }
        throw new ReservationCancellationException(ReservationCancellationException.Reason.OUTCOME_UNKNOWN);
    }

    private StoredReservationProcess load(long id, AuthenticatedReservationUser owner) {
        return processes.findOwned(id, owner).orElseThrow(() ->
                new ReservationProcessStateException(ReservationProcessStateException.Reason.NOT_FOUND));
    }
}
