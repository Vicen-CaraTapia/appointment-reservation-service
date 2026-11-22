package ar.edu.um.prog2.appointmentreservation.reservation.application.usecase;

import java.time.Clock;
import java.util.Objects;
import java.util.Optional;

import ar.edu.um.prog2.appointmentreservation.reservation.application.exception.InitialConfirmationException;
import ar.edu.um.prog2.appointmentreservation.reservation.application.exception.InitialConfirmationException.Reason;
import ar.edu.um.prog2.appointmentreservation.reservation.application.exception.ReservationProcessStateException;
import ar.edu.um.prog2.appointmentreservation.reservation.application.model.AuthenticatedReservationUser;
import ar.edu.um.prog2.appointmentreservation.reservation.application.model.ConfirmationPatient;
import ar.edu.um.prog2.appointmentreservation.reservation.application.model.InitialReservationConfirmation;
import ar.edu.um.prog2.appointmentreservation.reservation.application.model.StoredReservationProcess;
import ar.edu.um.prog2.appointmentreservation.reservation.application.port.out.AppointmentHoldConfirmer;
import ar.edu.um.prog2.appointmentreservation.reservation.application.port.out.CurrentReservationUser;
import ar.edu.um.prog2.appointmentreservation.reservation.application.port.out.ReservationProcessStore;
import ar.edu.um.prog2.appointmentreservation.reservation.application.service.ReservationSlotValidator;
import ar.edu.um.prog2.appointmentreservation.reservation.domain.model.ReservationProcessStatus;

/** Coordinates short local transactions and one remote POST, never a distributed transaction. */
public final class ConfirmReservationProcessUseCase {

	private final CurrentReservationUser currentUser;
	private final ReservationProcessStore processes;
	private final ReservationSlotValidator slots;
	private final AppointmentHoldConfirmer confirmer;
	private final Clock clock;

	public ConfirmReservationProcessUseCase(CurrentReservationUser currentUser, ReservationProcessStore processes,
			ReservationSlotValidator slots, AppointmentHoldConfirmer confirmer, Clock clock) {
		this.currentUser = Objects.requireNonNull(currentUser);
		this.processes = Objects.requireNonNull(processes);
		this.slots = Objects.requireNonNull(slots);
		this.confirmer = Objects.requireNonNull(confirmer);
		this.clock = Objects.requireNonNull(clock);
	}

	public StoredReservationProcess confirm(long id, ConfirmationPatient patient) {
		Objects.requireNonNull(patient, "Confirmation patient is required");
		if (id <= 0) {
			throw new IllegalArgumentException("A positive local process ID is required");
		}
		var owner = currentUser.requireAuthenticated();
		var observed = loadOwned(id, owner);
		if (isReplay(observed, patient)) {
			return observed;
		}
		if (observed.status() != ReservationProcessStatus.HELD || observed.hold() == null) {
			throw stateFailure(ReservationProcessStateException.Reason.CONFLICT);
		}
		requireUnexpired(observed);
		slots.validate(observed.selection());
		// Catalog may consume the remaining hold lifetime; check again before claiming.
		var now = clock.instant();
		if (!observed.hold().expiresAt().isAfter(now)) {
			throw stateFailure(ReservationProcessStateException.Reason.EXPIRED);
		}
		StoredReservationProcess claim;
		try {
			claim = processes.claimInitialConfirmation(id, observed.version(), owner, patient, now);
		} catch (ReservationProcessStateException failure) {
			if (failure.reason() != ReservationProcessStateException.Reason.CONFLICT) {
				throw failure;
			}
			var latest = loadOwned(id, owner);
			if (isReplay(latest, patient)) {
				return latest;
			}
			throw failure;
		}
		// Claim commit may also consume the deadline. Here the POST is known to be unsent.
		if (!claim.hold().expiresAt().isAfter(clock.instant())) {
			processes.releaseInitialConfirmation(id, claim.version(), owner);
			throw stateFailure(ReservationProcessStateException.Reason.EXPIRED);
		}
		return confirmClaim(claim, owner, patient);
	}

	private StoredReservationProcess confirmClaim(StoredReservationProcess claim, AuthenticatedReservationUser owner,
			ConfirmationPatient patient) {
		InitialReservationConfirmation accepted;
		try {
			accepted = confirmer.confirm(claim.hold(), claim.externalPatientId(), patient);
		} catch (InitialConfirmationException failure) {
			if (failure.reason().definitiveRejection()) {
				processes.releaseInitialConfirmation(claim.id(), claim.version(), owner);
			} else if (failure.reason() == Reason.RESERVATION_PROCESS_ALREADY_CONFIRMED) {
				var verified = acceptedLocally(claim, owner, patient);
				if (verified.isPresent()) {
					return verified.get();
				}
			}
			// Unknown outcomes keep the durable claim; no automatic POST retry or release.
			throw failure;
		}
		if (accepted == null || !claim.hold().reservationProcessId().equals(accepted.reservationProcessId())
				|| !patient.firstName().equals(accepted.patientFirstName())
				|| !patient.lastName().equals(accepted.patientLastName())) {
			throw new InitialConfirmationException(Reason.INVALID_RESPONSE);
		}
		try {
			return processes.recordInitialConfirmation(claim.id(), claim.version(), owner, accepted);
		} catch (ReservationProcessStateException failure) {
			// A failed local commit does not undo remote acceptance. Read once, never resend.
			return acceptedLocally(claim, owner, patient)
					.orElseThrow(() -> new InitialConfirmationException(Reason.LOCAL_STATE_FAILURE));
		}
	}

	private StoredReservationProcess loadOwned(long id, AuthenticatedReservationUser owner) {
		return processes.findOwned(id, owner)
				.orElseThrow(() -> stateFailure(ReservationProcessStateException.Reason.NOT_FOUND));
	}

	private void requireUnexpired(StoredReservationProcess process) {
		if (!process.hold().expiresAt().isAfter(clock.instant())) {
			throw stateFailure(ReservationProcessStateException.Reason.EXPIRED);
		}
	}

	private static boolean isReplay(StoredReservationProcess process, ConfirmationPatient patient) {
		if (process.status() != ReservationProcessStatus.CONFIRMATION_PENDING
				&& process.status() != ReservationProcessStatus.WAITING_FOR_PHONE) {
			return false;
		}
		if (!patient.firstName().equals(process.patientFirstName()) || !patient.lastName().equals(process.patientLastName())) {
			throw stateFailure(ReservationProcessStateException.Reason.CONFLICT);
		}
		return true;
	}

	private Optional<StoredReservationProcess> acceptedLocally(StoredReservationProcess claim,
			AuthenticatedReservationUser owner, ConfirmationPatient patient) {
		try {
			return processes.findOwned(claim.id(), owner).filter(latest ->
					latest.status() == ReservationProcessStatus.WAITING_FOR_PHONE
					&& latest.id() == claim.id() && latest.hold() != null
					&& latest.hold().holdId().equals(claim.hold().holdId())
					&& latest.hold().reservationProcessId().equals(claim.hold().reservationProcessId())
					&& latest.externalPatientId().equals(claim.externalPatientId())
					&& patient.firstName().equals(latest.patientFirstName())
					&& patient.lastName().equals(latest.patientLastName()));
		} catch (ReservationProcessStateException failure) {
			return Optional.empty();
		}
	}

	private static ReservationProcessStateException stateFailure(ReservationProcessStateException.Reason reason) {
		return new ReservationProcessStateException(reason);
	}
}
