package ar.edu.um.prog2.appointmentreservation.reservation.infrastructure.persistence.entity;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import ar.edu.um.prog2.appointmentreservation.reservation.domain.model.RestReservationTransitions;
import ar.edu.um.prog2.appointmentreservation.reservation.domain.model.RestReservationTransitions.Observation;
import ar.edu.um.prog2.appointmentreservation.reservation.application.model.TechnicalReservation;
import ar.edu.um.prog2.appointmentreservation.reservation.application.exception.OwnedReservationQueryException;
import ar.edu.um.prog2.appointmentreservation.reservation.application.exception.OwnedReservationQueryException.Reason;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import ar.edu.um.prog2.appointmentreservation.reservation.application.ExternalPatientId;
import ar.edu.um.prog2.appointmentreservation.reservation.domain.model.ReservationProcessStatus;
import ar.edu.um.prog2.appointmentreservation.reservation.application.model.ReservationActionEvent;
import ar.edu.um.prog2.appointmentreservation.reservation.domain.model.AsynchronousReservationTransitions;
import ar.edu.um.prog2.appointmentreservation.reservation.domain.model.AsynchronousReservationTransitions.Decision;
import ar.edu.um.prog2.appointmentreservation.reservation.domain.model.AsynchronousReservationTransitions.Outcome;
import ar.edu.um.prog2.appointmentreservation.reservation.domain.model.AsynchronousReservationTransitions.Signal;
import ar.edu.um.prog2.appointmentreservation.user.infrastructure.persistence.entity.UserEntity;

@Entity
@Table(name = "reservation_process")
public class ReservationProcessEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Version
	@Column(name = "version", nullable = false)
	private Long version;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "owner_user_id", nullable = false, updatable = false)
	private UserEntity owner;

	@Column(name = "professional_id", nullable = false, updatable = false)
	private long professionalId;

	@Column(name = "appointment_date", nullable = false, updatable = false)
	private LocalDate appointmentDate;

	@Column(name = "start_time", nullable = false, updatable = false)
	@JdbcTypeCode(SqlTypes.LOCAL_TIME)
	private LocalTime startTime;

	@Column(name = "external_patient_id", nullable = false, length = 100, updatable = false)
	private String externalPatientId;

	@Column(name = "hold_id", columnDefinition = "text")
	private String holdId;

	@Column(name = "external_reservation_process_id", columnDefinition = "text")
	private String externalReservationProcessId;

	@Enumerated(EnumType.STRING)
	@Column(name = "process_status", nullable = false, length = 50)
	private ReservationProcessStatus status;

	@Column(name = "expires_at")
	private Instant expiresAt;

	@Column(name = "patient_first_name", length = 100)
	private String patientFirstName;

	@Column(name = "patient_last_name", length = 100)
	private String patientLastName;

	@Column(name = "request_event_id", columnDefinition = "text")
	private String requestEventId;

	@Column(name = "external_reservation_id")
	private Long externalReservationId;

	@Enumerated(EnumType.STRING)
	@Column(name = "rest_observed_status", length = 20)
	private Observation restObservedStatus;

	@Column(name = "rest_verified_at")
	private Instant restVerifiedAt;

	@Column(name = "outcome_reason", columnDefinition = "text")
	private String outcomeReason;

	@Column(name = "current_phone_submission_event_id", columnDefinition = "text")
	private String currentPhoneSubmissionEventId;

	@Column(name = "cancellation_reason", length = 500)
	private String cancellationReason;

	@Column(name = "cancellation_requested_at")
	private Instant cancellationRequestedAt;

	@Column(name = "created_by", nullable = false, length = 50, updatable = false)
	private String createdBy;

	@Column(name = "created_date", nullable = false, updatable = false)
	private Instant createdDate;

	@Column(name = "last_modified_by", length = 50)
	private String lastModifiedBy;

	@Column(name = "last_modified_date")
	private Instant lastModifiedDate;

	protected ReservationProcessEntity() {
	}

	/** The caller supplies an application-derived patient ID and an existing local owner. */
	public ReservationProcessEntity(UserEntity owner, long professionalId, LocalDate appointmentDate,
			LocalTime startTime, String externalPatientId, String createdBy) {
		if (owner == null || owner.getId() == null) {
			throw new IllegalArgumentException("An existing final-user owner is required");
		}
		if (!ExternalPatientId.fromUserId(owner.getId()).equals(externalPatientId)) {
			throw new IllegalArgumentException("External patient ID must match the final-user owner");
		}
		this.owner = owner;
		this.professionalId = professionalId;
		this.appointmentDate = Objects.requireNonNull(appointmentDate, "Appointment date is required");
		this.startTime = Objects.requireNonNull(startTime, "Appointment start time is required");
		this.externalPatientId = externalPatientId;
		this.createdBy = auditActor(createdBy);
		this.status = ReservationProcessStatus.PENDING_HOLD;
	}

	/** Attach only a complete successful hold response; this method performs no remote operation. */
	public void markHeld(String holdId, String externalReservationProcessId, Instant expiresAt, String modifiedBy) {
		if (!ReservationProcessStatus.PENDING_HOLD.equals(status)) {
			throw new IllegalStateException("Only a pending process can receive its initial hold");
		}
		String checkedHold = externalId(holdId);
		String checkedProcess = externalId(externalReservationProcessId);
		Instant checkedExpiry = Objects.requireNonNull(expiresAt, "Hold expiration is required");
		String checkedActor = auditActor(modifiedBy);
		this.holdId = checkedHold;
		this.externalReservationProcessId = checkedProcess;
		this.expiresAt = checkedExpiry;
		if (id != null) {
			this.lastModifiedBy = checkedActor;
			this.lastModifiedDate = Instant.now();
		}
		this.status = ReservationProcessStatus.HELD;
	}

	/** Claims the local attempt before any remote confirmation is sent. */
	public void claimConfirmation(String firstName, String lastName, String modifiedBy) {
		if (!ReservationProcessStatus.HELD.equals(status)) {
			throw new IllegalStateException("Only a held process can start initial confirmation");
		}
		String checkedFirstName = patientName(firstName);
		String checkedLastName = patientName(lastName);
		String checkedActor = auditActor(modifiedBy);
		this.patientFirstName = checkedFirstName;
		this.patientLastName = checkedLastName;
		this.lastModifiedBy = checkedActor;
		this.lastModifiedDate = Instant.now();
		this.status = ReservationProcessStatus.CONFIRMATION_PENDING;
	}

	/** Caller must know the POST was not sent or was definitively rejected, never merely timed out. */
	public void releaseConfirmation(String modifiedBy) {
		if (!ReservationProcessStatus.CONFIRMATION_PENDING.equals(status)) {
			throw new IllegalStateException("Only a pending confirmation can be released");
		}
		String checkedActor = auditActor(modifiedBy);
		this.patientFirstName = null;
		this.patientLastName = null;
		this.lastModifiedBy = checkedActor;
		this.lastModifiedDate = Instant.now();
		this.status = ReservationProcessStatus.HELD;
	}

	/** Completes the claimed attempt; it does not represent a final appointment confirmation. */
	public void markWaitingForPhone(String firstName, String lastName, Instant confirmedExpiry, String modifiedBy) {
		if (!ReservationProcessStatus.CONFIRMATION_PENDING.equals(status)) {
			throw new IllegalStateException("Only a claimed process can receive its initial confirmation");
		}
		String checkedFirstName = patientName(firstName);
		String checkedLastName = patientName(lastName);
		if (!checkedFirstName.equals(patientFirstName) || !checkedLastName.equals(patientLastName)) {
			throw new IllegalStateException("Accepted patient details must match the claimed attempt");
		}
		Instant checkedExpiry = Objects.requireNonNull(confirmedExpiry, "Confirmation expiration is required")
				.truncatedTo(ChronoUnit.MICROS);
		String checkedActor = auditActor(modifiedBy);
		this.patientFirstName = checkedFirstName;
		this.patientLastName = checkedLastName;
		this.expiresAt = checkedExpiry;
		this.lastModifiedBy = checkedActor;
		this.lastModifiedDate = Instant.now();
		this.status = ReservationProcessStatus.WAITING_FOR_PHONE;
	}

	/** Caller holds the owner's process lock; no caller-supplied names or event IDs are copied. */
	public void reconcileRest(TechnicalReservation row, Instant queryStartedAt, Instant verifiedAt, String actor) {
		if (!Objects.equals(externalReservationProcessId, row.reservationProcessId())
				|| !externalPatientId.equals(row.externalPatientId())
				|| (externalReservationId != null && externalReservationId.longValue() != row.reservationId())
				|| professionalId != row.professionalId() || !appointmentDate.equals(row.appointmentDate())
				|| !startTime.equals(row.startTime()) || row.reservationId() <= 0) {
			throw new OwnedReservationQueryException(Reason.CONFLICTING_ASSOCIATION);
		}
		Instant changedAt = lastModifiedDate == null ? createdDate : lastModifiedDate;
		if ((changedAt != null && changedAt.isAfter(queryStartedAt))
				|| (restVerifiedAt != null && !restVerifiedAt.isBefore(queryStartedAt))) {
			throw new OwnedReservationQueryException(Reason.CONCURRENT_CHANGE);
		}
		var observed = Observation.valueOf(row.status().name());
		var decision = RestReservationTransitions.decide(status, observed);
		if (decision.outcome() == Outcome.CONFLICT) { throw new OwnedReservationQueryException(Reason.CONFLICTING_STATE); }
		String checkedActor = auditActor(actor);
		if (decision.outcome() == Outcome.APPLY) {
			status = decision.nextStatus();
			outcomeReason = null;
		}
		externalReservationId = row.reservationId();
		restObservedStatus = observed;
		restVerifiedAt = verifiedAt.truncatedTo(ChronoUnit.MICROS);
		lastModifiedBy = checkedActor;
		lastModifiedDate = restVerifiedAt;
	}

	public Observation getRestObservedStatus() { return restObservedStatus; }
	public Instant getRestVerifiedAt() { return restVerifiedAt; }

	/** Claims a confirmed process before the remote cancellation request. */
	public void claimCancellation(String reason, Instant requestedAt, String actor) {
		if (status == ReservationProcessStatus.CANCELLED) {
			return;
		}
		if (status != ReservationProcessStatus.CONFIRMED || externalReservationId == null) {
			throw new IllegalStateException("Only a confirmed reservation can be cancelled");
		}
		cancellationReason = checkedCancellationReason(reason);
		cancellationRequestedAt = Objects.requireNonNull(requestedAt, "Cancellation time is required");
		lastModifiedBy = auditActor(actor);
		lastModifiedDate = requestedAt;
		status = ReservationProcessStatus.CANCELLATION_PENDING;
	}

	/** Records correlated remote cancellation evidence; repeated evidence is idempotent. */
	public void markCancelled(long reservationId, Instant cancelledAt, String reason, String actor) {
		if (reservationId <= 0 || !Objects.equals(externalReservationId, reservationId)
				|| (status != ReservationProcessStatus.CANCELLATION_PENDING
						&& status != ReservationProcessStatus.CONFIRMED
						&& status != ReservationProcessStatus.CANCELLED)) {
			throw new IllegalStateException("Cancellation evidence does not match the reservation");
		}
		externalReservationId = reservationId;
		cancellationReason = checkedCancellationReason(reason == null ? cancellationReason : reason);
		restObservedStatus = Observation.CANCELLED;
		restVerifiedAt = Objects.requireNonNull(cancelledAt, "Cancellation time is required")
				.truncatedTo(ChronoUnit.MICROS);
		status = ReservationProcessStatus.CANCELLED;
		lastModifiedBy = auditActor(actor);
		lastModifiedDate = restVerifiedAt;
	}

	/** Storage guards for validated evidence; the adapter commits this effect with its inbox receipt. */
	public Decision applyAsynchronousEvidence(ReservationActionEvent event) {
		Objects.requireNonNull(event, "Validated reservation event is required");
		var blocked = evidenceOutcome(event);
		if (blocked != null) { return unchanged(blocked); }
		var decision = AsynchronousReservationTransitions.decide(status, event.signal());
		if (decision.outcome() != Outcome.APPLY) { return decision; }
		applyEvidenceFields(event);
		status = decision.nextStatus();
		lastModifiedBy = "catedra-events";
		lastModifiedDate = Instant.now();
		return decision;
	}


	private Outcome evidenceOutcome(ReservationActionEvent event) {
		if (!event.externalProcessId().equals(externalReservationProcessId) || !event.holdId().equals(holdId)) {
			return Outcome.CONFLICT;
		}
		if (event.signal() == Signal.PHONE_REQUESTED) { return phoneRequestOutcome(event); }
		if (requestEventId == null) { return status.isTerminal() ? Outcome.CONFLICT : Outcome.DEFER; }
		if (!requestEventId.equals(event.requestEventId()) || !event.hasSupportedReason()) { return Outcome.CONFLICT; }
		if (event.signal() == Signal.RESERVATION_CONFIRMED && !matchesConfirmation(event)) { return Outcome.CONFLICT; }
		if (event.signal() == Signal.RESERVATION_CANCELLED
				&& (externalReservationId == null || !externalReservationId.equals(event.externalReservationId()))) { return Outcome.CONFLICT; }
		if (status.isTerminal() && event.reason() != null && !event.reason().equals(outcomeReason)) { return Outcome.CONFLICT; }
		return null;
	}

	private Outcome phoneRequestOutcome(ReservationActionEvent event) {
		if (status.isTerminal() || requestEventId == null) { return null; }
		return requestEventId.equals(event.eventId()) && expiresAt.equals(event.expiresAt().truncatedTo(ChronoUnit.MICROS))
				? Outcome.IGNORE : Outcome.CONFLICT;
	}

	private boolean matchesConfirmation(ReservationActionEvent event) {
		var selection = event.confirmedSelection();
		return selection.professionalId() == professionalId && selection.date().equals(appointmentDate)
				&& selection.startTime().equals(startTime)
				&& (externalReservationId == null || externalReservationId.equals(event.externalReservationId()));
	}

	private void applyEvidenceFields(ReservationActionEvent event) {
		switch (event.signal()) {
			case PHONE_REQUESTED -> {
				requestEventId = event.eventId();
				expiresAt = event.expiresAt().truncatedTo(ChronoUnit.MICROS);
			}
			case RESERVATION_CONFIRMED -> {
				externalReservationId = event.externalReservationId();
				outcomeReason = null;
			}
				case RESERVATION_CANCELLED -> {
					externalReservationId = event.externalReservationId();
					cancellationReason = checkedCancellationReason(event.reason() == null ? cancellationReason : event.reason());
					restObservedStatus = Observation.CANCELLED;
					restVerifiedAt = event.occurredAt().truncatedTo(ChronoUnit.MICROS);
				}
			case PHONE_REJECTED -> {
				outcomeReason = event.reason();
				expiresAt = event.expiresAt().truncatedTo(ChronoUnit.MICROS);
			}
			case PROCESS_EXPIRED, PROCESS_INVALID -> outcomeReason = event.reason();
			default -> throw new IllegalStateException("Only incoming evidence may update event persistence");
		}
	}

	private Decision unchanged(Outcome outcome) { return new Decision(outcome, status); }

	/** Must be committed atomically with the new intent; this method never publishes. */
	public void preparePhoneSubmission(String eventId, String actor) {
		var next = AsynchronousReservationTransitions.decide(status, Signal.PHONE_SUBMISSION_ACCEPTED);
		if (next.outcome() != Outcome.APPLY || requestEventId == null) {
			throw new IllegalStateException("A current phone request is required before preparing submission");
		}
		String checkedEvent = externalId(eventId);
		String checkedActor = auditActor(actor);
		currentPhoneSubmissionEventId = checkedEvent;
		outcomeReason = null;
		status = next.nextStatus();
		lastModifiedBy = checkedActor;
		lastModifiedDate = Instant.now();
	}

	/** Broker acknowledgment is evidence of publication only, never of final reservation acceptance. */
	public void recordPhonePublication(String eventId) {
		if (!Objects.equals(currentPhoneSubmissionEventId, eventId)) {
			throw new IllegalStateException("Publication does not match the current submission");
		}
		var next = AsynchronousReservationTransitions.decide(status, Signal.PHONE_PUBLICATION_ACKNOWLEDGED);
		if (next.outcome() == Outcome.APPLY) {
			status = next.nextStatus();
			lastModifiedBy = "phone-publisher";
			lastModifiedDate = Instant.now();
		}
	}

	private static String patientName(String value) {
		if (value == null || value.isBlank() || value.length() < 2 || value.length() > 100) {
			throw new IllegalArgumentException("Patient names must be nonblank and contain 2 to 100 characters");
		}
		return value;
	}

	@PrePersist
	private void recordCreationDate() {
		createdDate = Instant.now();
	}

	@PreUpdate
	private void recordModificationDate() {
		lastModifiedDate = Instant.now();
	}

	private static String externalId(String value) {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException("A nonblank external identifier is required");
		}
		return value;
	}

	private static String auditActor(String value) {
		if (value == null || value.isBlank() || value.length() > 50) {
			throw new IllegalArgumentException("A nonblank audit actor of at most 50 characters is required");
		}
		return value;
	}

	private static String checkedCancellationReason(String value) {
		if (value != null && value.length() > 500) {
			throw new IllegalArgumentException("Cancellation reason must not exceed 500 characters");
		}
		return value;
	}

	public Long getId() { return id; }
	public Long getVersion() { return version; }
	public UserEntity getOwner() { return owner; }
	public long getProfessionalId() { return professionalId; }
	public LocalDate getAppointmentDate() { return appointmentDate; }
	public LocalTime getStartTime() { return startTime; }
	public String getExternalPatientId() { return externalPatientId; }
	public String getHoldId() { return holdId; }
	public String getExternalReservationProcessId() { return externalReservationProcessId; }
	public ReservationProcessStatus getStatus() { return status; }
	public Instant getExpiresAt() { return expiresAt; }
	public String getPatientFirstName() { return patientFirstName; }
	public String getPatientLastName() { return patientLastName; }
	public String getRequestEventId() { return requestEventId; }
	public Long getExternalReservationId() { return externalReservationId; }
	public String getOutcomeReason() { return outcomeReason; }
	public String getCurrentPhoneSubmissionEventId() { return currentPhoneSubmissionEventId; }
	public String getCancellationReason() { return cancellationReason; }
	public Instant getCancellationRequestedAt() { return cancellationRequestedAt; }
	public String getCreatedBy() { return createdBy; }
	public Instant getCreatedDate() { return createdDate; }
	public String getLastModifiedBy() { return lastModifiedBy; }
	public Instant getLastModifiedDate() { return lastModifiedDate; }
}
