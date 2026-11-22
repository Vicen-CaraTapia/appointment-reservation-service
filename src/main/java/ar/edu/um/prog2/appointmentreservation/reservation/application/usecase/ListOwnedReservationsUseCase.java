package ar.edu.um.prog2.appointmentreservation.reservation.application.usecase;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.stream.Collectors;
import ar.edu.um.prog2.appointmentreservation.reservation.application.model.ReservationReconciliation;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import ar.edu.um.prog2.appointmentreservation.reservation.application.ExternalPatientId;
import ar.edu.um.prog2.appointmentreservation.reservation.application.exception.OwnedReservationQueryException;
import ar.edu.um.prog2.appointmentreservation.reservation.application.exception.OwnedReservationQueryException.Reason;
import ar.edu.um.prog2.appointmentreservation.reservation.application.model.AuthenticatedReservationUser;
import ar.edu.um.prog2.appointmentreservation.reservation.application.model.OwnedReservationPage;
import ar.edu.um.prog2.appointmentreservation.reservation.application.model.OwnedReservationQuery;
import ar.edu.um.prog2.appointmentreservation.reservation.application.model.ReservationAssociation;
import ar.edu.um.prog2.appointmentreservation.reservation.application.model.ReservationListQuery;
import ar.edu.um.prog2.appointmentreservation.reservation.application.model.TechnicalReservation;
import ar.edu.um.prog2.appointmentreservation.reservation.application.model.TechnicalReservationPage;
import ar.edu.um.prog2.appointmentreservation.reservation.application.port.out.CurrentReservationUser;
import ar.edu.um.prog2.appointmentreservation.reservation.application.port.out.ReservationProcessStore;
import ar.edu.um.prog2.appointmentreservation.reservation.application.port.out.TechnicalReservationSource;

/**
 * No owner is inferred from names or from the remote patient filter alone.
 * Unassociated rows are excluded; contradictory associations fail the entire query.
 * All bounded remote pages are read before authorization and local pagination, so remote totals
 * and links cannot reveal unassociated rows. Partial/changing results fail rather than truncate.
 * REST pagination is not a transactional snapshot: absence never proves local deletion or failure.
 * Only the complete authorized batch is reconciled, in one short local transaction after remote I/O.
 * Returned items distinguish committed local state from the external observation.
 */
public final class ListOwnedReservationsUseCase {
	private static final int REMOTE_PAGE_SIZE = 100;
	private static final int MAX_REMOTE_PAGES = 10;
	private final Clock clock;
	private final CurrentReservationUser currentUser;
	private final TechnicalReservationSource remote;
	private final ReservationProcessStore processes;

	public ListOwnedReservationsUseCase(CurrentReservationUser currentUser, TechnicalReservationSource remote,
			ReservationProcessStore processes, Clock clock) {
		this.clock = Objects.requireNonNull(clock);
		this.currentUser = Objects.requireNonNull(currentUser);
		this.remote = Objects.requireNonNull(remote);
		this.processes = Objects.requireNonNull(processes);
	}

	public OwnedReservationPage list(OwnedReservationQuery query) {
		var owner = currentUser.requireAuthenticated();
		Objects.requireNonNull(query, "Owned reservation filters are required");
		Instant queryStartedAt = clock.instant();
		var rows = collect(query, ExternalPatientId.fromUserId(owner.userId()));
		var authorized = authorize(rows, owner);
		var verified = reconcile(owner, authorized, queryStartedAt);
		long offset = (long) query.page() * query.size();
		int from = (int) Math.min(offset, verified.size());
		int to = (int) Math.min(offset + query.size(), verified.size());
		return new OwnedReservationPage(verified.subList(from, to), verified.size(), query.page(), query.size());
	}

	private List<OwnedReservationPage.Item> reconcile(AuthenticatedReservationUser owner,
			List<OwnedReservationPage.Item> authorized, Instant queryStartedAt) {
		if (authorized.isEmpty()) { return List.of(); }
		var result = processes.reconcileReservations(owner, authorized, queryStartedAt);
		var byId = result.stream().collect(Collectors.toMap(ReservationReconciliation::localProcessId, item -> item));
		if (byId.size() != authorized.size()) { throw failure(Reason.INVALID_RESPONSE); }
		return authorized.stream().map(item -> {
			var verified = byId.get(item.localProcessId());
			if (verified == null || verified.status() == null || verified.verifiedAt() == null
					|| verified.verifiedAt().isBefore(queryStartedAt.truncatedTo(ChronoUnit.MICROS))) {
				throw failure(Reason.INVALID_RESPONSE);
			}
			return new OwnedReservationPage.Item(item.localProcessId(), item.reservation(), verified.status(), verified.verifiedAt());
		}).toList();
	}

	private List<TechnicalReservation> collect(OwnedReservationQuery query, String patientId) {
		var rows = new ArrayList<TechnicalReservation>();
		Set<String> processIds = new HashSet<>();
		Set<Long> reservationIds = new HashSet<>();
		Long expectedTotal = null;
		for (int page = 0; page < MAX_REMOTE_PAGES; page++) {
			// Empty sort delegates to the documented date/time descending order. Never follow external Link URLs.
			var result = remote.list(new ReservationListQuery(query.status(), query.from(), query.to(), patientId,
					page, REMOTE_PAGE_SIZE, List.of()));
			validatePage(result, expectedTotal);
			expectedTotal = result.totalCount();
			for (var row : result.reservations()) {
				validateFilters(row, query, patientId);
				validateUniqueIdentity(row, processIds, reservationIds);
				rows.add(row);
			}
			if (rows.size() > expectedTotal) { throw failure(Reason.INVALID_RESPONSE); }
			if (rows.size() == expectedTotal) { return List.copyOf(rows); }
			if (result.reservations().size() < REMOTE_PAGE_SIZE) { throw failure(Reason.INCOMPLETE_RESULT); }
		}
		throw failure(Reason.INCOMPLETE_RESULT);
	}

	private static void validatePage(TechnicalReservationPage result, Long expectedTotal) {
		if (result == null || result.totalCount() < 0 || result.reservations().size() > REMOTE_PAGE_SIZE) {
			throw failure(Reason.INVALID_RESPONSE);
		}
		if (result.totalCount() > (long) REMOTE_PAGE_SIZE * MAX_REMOTE_PAGES
				|| (expectedTotal != null && expectedTotal.longValue() != result.totalCount())) {
			throw failure(Reason.INCOMPLETE_RESULT);
		}
	}

	private static void validateUniqueIdentity(TechnicalReservation row, Set<String> processIds, Set<Long> reservationIds) {
		if (!processIds.add(row.reservationProcessId()) || !reservationIds.add(row.reservationId())) {
			throw failure(Reason.CONFLICTING_ASSOCIATION);
		}
	}

	private static void validateFilters(TechnicalReservation row, OwnedReservationQuery query, String patientId) {
		if (!patientId.equals(row.externalPatientId()) || (query.status() != null && query.status() != row.status())
				|| (query.from() != null && row.appointmentDate().isBefore(query.from()))
				|| (query.to() != null && row.appointmentDate().isAfter(query.to()))) {
			throw failure(Reason.INVALID_RESPONSE);
		}
	}

	private List<OwnedReservationPage.Item> authorize(List<TechnicalReservation> rows, AuthenticatedReservationUser owner) {
		if (rows.isEmpty()) { return List.of(); }
		var processIds = new HashSet<String>();
		var reservationIds = new HashSet<Long>();
		rows.forEach(row -> { processIds.add(row.reservationProcessId()); reservationIds.add(row.reservationId()); });
		var associations = processes.findReservationAssociations(processIds, reservationIds);
		Map<String, ReservationAssociation> byProcess = new HashMap<>();
		Map<Long, ReservationAssociation> byReservation = new HashMap<>();
		for (var association : associations) {
			if (byProcess.put(association.externalProcessId(), association) != null) { throw failure(Reason.CONFLICTING_ASSOCIATION); }
			if (association.externalReservationId() != null && byReservation.put(association.externalReservationId(), association) != null) {
				throw failure(Reason.CONFLICTING_ASSOCIATION);
			}
		}
		var authorized = new ArrayList<OwnedReservationPage.Item>();
		for (var row : rows) {
			var association = byProcess.get(row.reservationProcessId());
			var reservationMatch = byReservation.get(row.reservationId());
			if (association == null && reservationMatch == null) { continue; }
			validateAssociation(row, association, reservationMatch, owner);
			authorized.add(new OwnedReservationPage.Item(association.localProcessId(), row));
		}
		return List.copyOf(authorized);
	}

	private static void validateAssociation(TechnicalReservation row, ReservationAssociation association,
			ReservationAssociation reservationMatch, AuthenticatedReservationUser owner) {
		if (association == null || (reservationMatch != null && reservationMatch.localProcessId() != association.localProcessId())) {
			throw failure(Reason.CONFLICTING_ASSOCIATION);
		}
		if (association.ownerUserId() != owner.userId() || !association.externalPatientId().equals(row.externalPatientId())
				|| (association.externalReservationId() != null && association.externalReservationId().longValue() != row.reservationId())
				|| association.professionalId() != row.professionalId() || !association.appointmentDate().equals(row.appointmentDate())
				|| !association.startTime().equals(row.startTime())) {
			throw failure(Reason.CONFLICTING_ASSOCIATION);
		}
	}

	private static OwnedReservationQueryException failure(Reason reason) { return new OwnedReservationQueryException(reason); }
}
