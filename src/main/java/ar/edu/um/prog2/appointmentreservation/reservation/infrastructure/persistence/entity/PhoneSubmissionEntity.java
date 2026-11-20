package ar.edu.um.prog2.appointmentreservation.reservation.infrastructure.persistence.entity;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import ar.edu.um.prog2.appointmentreservation.reservation.application.model.NewPhoneSubmission;
import ar.edu.um.prog2.appointmentreservation.reservation.application.model.PhoneSubmissionIntent;
import ar.edu.um.prog2.appointmentreservation.reservation.application.model.PhoneSubmissionIntent.State;

@Entity
@Table(name = "reservation_phone_submission")
public class PhoneSubmissionEntity {
	@Id
	@Column(name = "event_id", columnDefinition = "text")
	private String eventId;
	@Column(name = "local_process_id", nullable = false, updatable = false)
	private long localProcessId;
	@Column(name = "request_event_id", nullable = false, updatable = false, columnDefinition = "text")
	private String requestEventId;
	@Column(name = "external_process_id", nullable = false, updatable = false, columnDefinition = "text")
	private String externalProcessId;
	@Column(name = "hold_id", nullable = false, updatable = false, columnDefinition = "text")
	private String holdId;
	@Column(name = "technical_group_id", nullable = false, updatable = false, columnDefinition = "text")
	private String technicalGroupId;
	@Column(name = "normalized_phone", nullable = false, updatable = false, length = 16)
	private String normalizedPhone;
	@Column(name = "occurred_at", nullable = false, updatable = false)
	private Instant occurredAt;
	@Enumerated(EnumType.STRING)
	@Column(name = "publication_state", nullable = false, length = 16)
	private State state;
	@Column(name = "created_date", nullable = false, updatable = false)
	private Instant createdDate;
	@Column(name = "published_date")
	private Instant publishedDate;
	@Column(name = "closed_date")
	private Instant closedDate;

	protected PhoneSubmissionEntity() { }

	public PhoneSubmissionEntity(ReservationProcessEntity process, NewPhoneSubmission input, Instant now) {
		this.eventId = input.eventId();
		this.localProcessId = process.getId();
		this.requestEventId = process.getRequestEventId();
		this.externalProcessId = process.getExternalReservationProcessId();
		this.holdId = process.getHoldId();
		this.technicalGroupId = input.technicalGroupId();
		this.normalizedPhone = input.normalizedPhone();
		// Canonicalize backend-generated time before the first return: retries retain identical payloads.
		this.occurredAt = now.truncatedTo(ChronoUnit.MICROS);
		this.state = State.PENDING;
	}

	public void published(Instant acknowledgedAt) {
		if (state == State.PENDING) {
			state = State.PUBLISHED;
			publishedDate = acknowledgedAt.truncatedTo(ChronoUnit.MICROS);
		}
	}

	public void close(Instant now) {
		if (state != State.CLOSED) {
			state = State.CLOSED;
			closedDate = now.truncatedTo(ChronoUnit.MICROS);
		}
	}

	public boolean matches(ReservationProcessEntity process, NewPhoneSubmission input) {
		return sameCorrelation(process, input.technicalGroupId()) && normalizedPhone.equals(input.normalizedPhone());
	}

	public boolean sameCorrelation(ReservationProcessEntity process, String group) {
		return localProcessId == process.getId() && requestEventId.equals(process.getRequestEventId())
				&& externalProcessId.equals(process.getExternalReservationProcessId()) && holdId.equals(process.getHoldId())
				&& technicalGroupId.equals(group);
	}

	public PhoneSubmissionIntent observation() {
		return new PhoneSubmissionIntent(eventId, localProcessId, requestEventId, externalProcessId, holdId,
				technicalGroupId, normalizedPhone, occurredAt, state);
	}
	@PrePersist
	private void created() { createdDate = Instant.now(); }
	public String getEventId() { return eventId; }
	public long getLocalProcessId() { return localProcessId; }
	public State getState() { return state; }
	@Override
	public String toString() { return "PhoneSubmissionEntity[redacted]"; }
}
