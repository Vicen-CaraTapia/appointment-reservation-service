package ar.edu.um.prog2.appointmentreservation.reservation.infrastructure.persistence.entity;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import ar.edu.um.prog2.appointmentreservation.reservation.application.model.ReservationEventReceipt;
import ar.edu.um.prog2.appointmentreservation.reservation.application.model.ReservationEventReceipt.State;

@Entity
@Table(name = "reservation_event_inbox")
public class ReservationEventInboxEntity {
	@Id
	@Column(name = "event_id", columnDefinition = "text")
	private String eventId;
	@Column(name = "external_process_id", nullable = false, columnDefinition = "text")
	private String externalProcessId;
	@Column(name = "payload", nullable = false, columnDefinition = "text")
	private String payload;
	@Column(name = "fingerprint", nullable = false, length = 64)
	private String fingerprint;
	@Enumerated(EnumType.STRING)
	@Column(name = "handling_status", nullable = false, length = 20)
	private State state;
	@Column(name = "local_process_id")
	private Long localProcessId;
	@Column(name = "content_conflict", nullable = false)
	private boolean contentConflict;
	@Column(name = "last_conflicting_fingerprint", length = 64)
	private String lastConflictingFingerprint;
	@Column(name = "last_conflict_date")
	private Instant lastConflictDate;
	@Column(name = "created_date", nullable = false)
	private Instant createdDate;
	@Column(name = "handled_date")
	private Instant handledDate;

	protected ReservationEventInboxEntity() { }

	public void recordContentConflict(String conflictingFingerprint) {
		contentConflict = true;
		lastConflictingFingerprint = conflictingFingerprint;
		lastConflictDate = Instant.now();
	}

	public void handled(State state, long localProcessId) {
		if (this.state != State.PENDING || state == State.PENDING || contentConflict) {
			throw new IllegalStateException("Only an unambiguous pending event may be handled");
		}
		this.state = state;
		this.localProcessId = localProcessId;
		handledDate = Instant.now();
	}

	public String getEventId() { return eventId; }
	public String getPayload() { return payload; }
	public String getFingerprint() { return fingerprint; }
	public State getState() { return state; }
	public boolean hasContentConflict() { return contentConflict; }
	public ReservationEventReceipt receipt() { return new ReservationEventReceipt(eventId, state, localProcessId, contentConflict); }

	@Override
	public String toString() { return "ReservationEventInboxEntity[redacted]"; }
}
