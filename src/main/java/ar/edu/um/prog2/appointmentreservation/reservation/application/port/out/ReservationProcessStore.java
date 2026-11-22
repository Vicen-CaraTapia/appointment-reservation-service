package ar.edu.um.prog2.appointmentreservation.reservation.application.port.out;

import ar.edu.um.prog2.appointmentreservation.reservation.application.model.OwnedReservationPage;
import ar.edu.um.prog2.appointmentreservation.reservation.application.model.ReservationReconciliation;
import java.util.List;
import java.util.Set;
import ar.edu.um.prog2.appointmentreservation.reservation.application.model.ReservationAssociation;

import java.util.Optional;
import java.time.Instant;
import ar.edu.um.prog2.appointmentreservation.reservation.application.model.StoredReservationProgress;

import ar.edu.um.prog2.appointmentreservation.reservation.application.model.AppointmentHold;
import ar.edu.um.prog2.appointmentreservation.reservation.application.model.AuthenticatedReservationUser;
import ar.edu.um.prog2.appointmentreservation.reservation.application.model.HeldReservationProcess;
import ar.edu.um.prog2.appointmentreservation.reservation.application.model.StartReservationCommand;
import ar.edu.um.prog2.appointmentreservation.reservation.application.model.StoredReservationProcess;
import ar.edu.um.prog2.appointmentreservation.reservation.application.model.InitialReservationConfirmation;
import ar.edu.um.prog2.appointmentreservation.reservation.application.model.ConfirmationPatient;

public interface ReservationProcessStore {

	/**
	 * Revalidates the complete authorized batch under local locks and commits all effects atomically.
	 * queryStartedAt precedes remote I/O. Newer local writes or contradictory evidence reject the batch.
	 * No remote calls, synthetic event IDs or changes inferred from missing rows are permitted.
	 */
	List<ReservationReconciliation> reconcileReservations(
			AuthenticatedReservationUser owner,
			List<OwnedReservationPage.Item> observations,
			Instant queryStartedAt);


	/** Bounded local identity lookup by either external ID, including contradictions across owners; no state mutation. */
	List<ReservationAssociation>
			findReservationAssociations(Set<String> processIds, Set<Long> reservationIds);


	/**
	 * Atomically saves a local HELD process owned by the authenticated user, preserving
	 * the successful hold's external identifiers and expiration. Patient identity is
	 * derived from this owner, never accepted as an independent ownership parameter.
	 * Local persistence cannot roll back the already-created remote hold.
	 */
	HeldReservationProcess createHeld(AuthenticatedReservationUser owner, StartReservationCommand selection,
			AppointmentHold hold);

	/** Missing and foreign-owned IDs are indistinguishable; no live persistence entity is returned. */
	Optional<StoredReservationProcess> findOwned(long id, AuthenticatedReservationUser owner);

	/** One read-only snapshot, filtered by local process ID and trusted owner. */
	Optional<StoredReservationProgress> findOwnedProgress(long id, AuthenticatedReservationUser owner);

	/** Commits one HELD-to-pending claim before POST; now comes from the application's controlled Clock. */
	StoredReservationProcess claimInitialConfirmation(long id, long expectedVersion,
			AuthenticatedReservationUser owner, ConfirmationPatient patient, Instant now);

	/**
	 * Releases only this versioned pending attempt. Caller must have definitive evidence that
	 * no remote acceptance occurred (unsent request or known rejection), never just a timeout.
	 */
	StoredReservationProcess releaseInitialConfirmation(long id, long expectedVersion, AuthenticatedReservationUser owner);

	/**
	 * Records validated initial acceptance only if owner, version, pending claim, patient and
	 * external process ID still match. Commits locally before returning; cannot roll back cátedra.
	 */
	StoredReservationProcess recordInitialConfirmation(long id, long expectedVersion,
			AuthenticatedReservationUser owner, InitialReservationConfirmation confirmation);
}
