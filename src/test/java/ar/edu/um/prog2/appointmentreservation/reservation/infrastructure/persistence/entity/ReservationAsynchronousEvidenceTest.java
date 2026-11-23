package ar.edu.um.prog2.appointmentreservation.reservation.infrastructure.persistence.entity;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import ar.edu.um.prog2.appointmentreservation.reservation.application.model.ReservationActionEvent;
import ar.edu.um.prog2.appointmentreservation.reservation.application.model.StartReservationCommand;
import ar.edu.um.prog2.appointmentreservation.reservation.domain.model.AsynchronousReservationTransitions.Signal;
import ar.edu.um.prog2.appointmentreservation.reservation.domain.model.AsynchronousReservationTransitions.Outcome;
import ar.edu.um.prog2.appointmentreservation.reservation.domain.model.ReservationProcessStatus;
import ar.edu.um.prog2.appointmentreservation.user.infrastructure.persistence.entity.UserEntity;
import ar.edu.um.prog2.appointmentreservation.user.infrastructure.security.FinalUserSecurityConfiguration;

import static org.assertj.core.api.Assertions.assertThat;

class ReservationAsynchronousEvidenceTest {
	private static final String HASH = new FinalUserSecurityConfiguration().passwordEncoder().encode("test-only-event-passphrase");
	private static final Instant TIME = Instant.parse("2026-11-20T12:00:00.123456789Z");
	private static final StartReservationCommand SELECTION = new StartReservationCommand(202, LocalDate.of(2030, 1, 3), LocalTime.of(18, 0));

	@Test
	void aCorrelatedRequestResolvesPendingRestWithoutChangingItsOwnerOrPatient() {
		var process = claimed();
		assertThat(process.applyAsynchronousEvidence(request("process", "hold")).outcome()).isEqualTo(Outcome.APPLY);
		assertThat(process.getStatus()).isEqualTo(ReservationProcessStatus.WAITING_FOR_PHONE);
		assertThat(process.getRequestEventId()).isEqualTo("request");
		assertThat(process.getExpiresAt()).isEqualTo(TIME.plusSeconds(300).truncatedTo(ChronoUnit.MICROS));
		assertThat(process.getOwner().getId()).isEqualTo(42);
		assertThat(process.getPatientFirstName()).isEqualTo("Ana");
		assertThat(process.getLastModifiedBy()).isEqualTo("catedra-events");
	}

	@ParameterizedTest
	@ValueSource(strings = {"process", "other"})
	void mismatchingProcessOrHoldNeverAffectsTheClaim(String processId) {
		var process = claimed();
		assertThat(process.applyAsynchronousEvidence(request(processId, "other-hold")).outcome()).isEqualTo(Outcome.CONFLICT);
		assertThat(process.getStatus()).isEqualTo(ReservationProcessStatus.CONFIRMATION_PENDING);
		assertThat(process.getRequestEventId()).isNull();
	}

	@Test
	void anUnclaimedHoldDefersEvidenceAndResultsWaitForTheirOriginalRequest() {
		var process = claimed();
		ReflectionTestUtils.setField(process, "status", ReservationProcessStatus.HELD);
		assertThat(process.applyAsynchronousEvidence(request("process", "hold")).outcome()).isEqualTo(Outcome.DEFER);
		var claimed = claimed();
		assertThat(claimed.applyAsynchronousEvidence(result("confirmed", Signal.RESERVATION_CONFIRMED, "request", 8251L, SELECTION, null)).outcome())
				.isEqualTo(Outcome.DEFER);
	}

	@Test
	void duplicateRequestIsIgnoredAndAnotherRequestOrChangedDeadlineIsAConflict() {
		var process = waiting();
		var audit = process.getLastModifiedDate();
		assertThat(process.applyAsynchronousEvidence(request("process", "hold")).outcome()).isEqualTo(Outcome.IGNORE);
		assertThat(process.getLastModifiedDate()).isEqualTo(audit);
		var changed = new ReservationActionEvent("request", Signal.PHONE_REQUESTED, "process", "hold", null,
				TIME, TIME.plusSeconds(600), null, null, null);
		assertThat(process.applyAsynchronousEvidence(changed).outcome()).isEqualTo(Outcome.CONFLICT);
		var other = new ReservationActionEvent("another-request", Signal.PHONE_REQUESTED, "process", "hold", null,
				TIME, TIME.plusSeconds(300), null, null, null);
		assertThat(process.applyAsynchronousEvidence(other).outcome()).isEqualTo(Outcome.CONFLICT);
	}

	@Test
	void finalConfirmationRequiresMatchingRequestAndSelectionAndCannotBeOverwritten() {
		var process = waiting();
		assertThat(process.applyAsynchronousEvidence(result("bad-request", Signal.RESERVATION_CONFIRMED, "other", 8251L, SELECTION, null)).outcome())
				.isEqualTo(Outcome.CONFLICT);
		assertThat(process.applyAsynchronousEvidence(result("bad-slot", Signal.RESERVATION_CONFIRMED, "request", 8251L,
				new StartReservationCommand(203, SELECTION.date(), SELECTION.startTime()), null)).outcome()).isEqualTo(Outcome.CONFLICT);
		assertThat(process.applyAsynchronousEvidence(result("confirmed", Signal.RESERVATION_CONFIRMED, "request", 8251L, SELECTION, null)).outcome())
				.isEqualTo(Outcome.APPLY);
		assertThat(process.getExternalReservationId()).isEqualTo(8251);
		assertThat(process.applyAsynchronousEvidence(result("duplicate-result", Signal.RESERVATION_CONFIRMED, "request", 8251L, SELECTION, null)).outcome())
				.isEqualTo(Outcome.IGNORE);
		assertThat(process.applyAsynchronousEvidence(result("conflicting-result", Signal.RESERVATION_CONFIRMED, "request", 8252L, SELECTION, null)).outcome())
				.isEqualTo(Outcome.CONFLICT);
		assertThat(process.applyAsynchronousEvidence(result("late-expiry", Signal.PROCESS_EXPIRED, "request", null, null, "HOLD_EXPIRED")).outcome())
				.isEqualTo(Outcome.CONFLICT);
		assertThat(process.applyAsynchronousEvidence(request("process", "hold")).outcome()).isEqualTo(Outcome.IGNORE);
		assertThat(process.getStatus()).isEqualTo(ReservationProcessStatus.CONFIRMED);
	}

	@ParameterizedTest @ValueSource(strings={"process","hold","request","reservation","professional","date","time"})
	void cancellationRejectsContradictoryCorrelation(String mismatch) {
		var process = waiting();
		process.applyAsynchronousEvidence(result("confirmed",Signal.RESERVATION_CONFIRMED,"request",8251L,SELECTION,null));
		var selected = new StartReservationCommand(mismatch.equals("professional")?203:202,
				mismatch.equals("date")?SELECTION.date().plusDays(1):SELECTION.date(),
				mismatch.equals("time")?SELECTION.startTime().plusMinutes(30):SELECTION.startTime());
		var event = new ReservationActionEvent("cancel",Signal.RESERVATION_CANCELLED,
				mismatch.equals("process")?"other":"process",mismatch.equals("hold")?"other":"hold",
				mismatch.equals("request")?"other":"request",TIME,null,mismatch.equals("reservation")?8252L:8251L,selected,null);
		assertThat(process.applyAsynchronousEvidence(event).outcome()).isEqualTo(Outcome.CONFLICT);
		assertThat(process.getStatus()).isEqualTo(ReservationProcessStatus.CONFIRMED);
	}

	@Test
	void rejectionIsCorrectableButUnknownReasonsAreRetainedAsConflicts() {
		var process = waiting();
		ReflectionTestUtils.setField(process, "status", ReservationProcessStatus.PHONE_SUBMISSION_PENDING);
		var unknown = new ReservationActionEvent("unknown", Signal.PHONE_REJECTED, "process", "hold", "request", TIME,
				TIME.plusSeconds(300), null, null, "UNSUPPORTED_REASON");
		assertThat(process.applyAsynchronousEvidence(unknown).outcome()).isEqualTo(Outcome.CONFLICT);
		var rejected = new ReservationActionEvent("rejected", Signal.PHONE_REJECTED, "process", "hold", "request", TIME,
				TIME.plusSeconds(300), null, null, "INVALID_PHONE_NUMBER");
		assertThat(process.applyAsynchronousEvidence(rejected).outcome()).isEqualTo(Outcome.APPLY);
		assertThat(process.getStatus()).isEqualTo(ReservationProcessStatus.WAITING_FOR_PHONE);
		assertThat(process.getOutcomeReason()).isEqualTo("INVALID_PHONE_NUMBER");
	}

	@ParameterizedTest
	@ValueSource(booleans = {true, false})
	void expiryAndInvalidityRemainTerminal(boolean expired) {
		var process = waiting();
		var event = result("final", expired ? Signal.PROCESS_EXPIRED : Signal.PROCESS_INVALID, "request", null, null,
				expired ? "HOLD_EXPIRED" : "REQUEST_EVENT_MISMATCH");
		assertThat(process.applyAsynchronousEvidence(event).outcome()).isEqualTo(Outcome.APPLY);
		assertThat(process.getStatus().isTerminal()).isTrue();
		assertThat(process.applyAsynchronousEvidence(event).outcome()).isEqualTo(Outcome.IGNORE);
		assertThat(process.applyAsynchronousEvidence(result("confirmed", Signal.RESERVATION_CONFIRMED, "request", 8251L, SELECTION, null)).outcome())
				.isEqualTo(Outcome.CONFLICT);
	}

	private static ReservationActionEvent request(String process, String hold) {
		return new ReservationActionEvent("request", Signal.PHONE_REQUESTED, process, hold, null, TIME, TIME.plusSeconds(300), null, null, null);
	}
	private static ReservationActionEvent result(String id, Signal signal, String request, Long reservation,
			StartReservationCommand selection, String reason) {
		return new ReservationActionEvent(id, signal, "process", "hold", request, TIME, null, reservation, selection, reason);
	}
	private static ReservationProcessEntity waiting() {
		var process = claimed();
		process.applyAsynchronousEvidence(request("process", "hold"));
		return process;
	}
	private static ReservationProcessEntity claimed() {
		var owner = new UserEntity("alice", HASH, null, null, "alice@example.test", null, "es", "alice");
		ReflectionTestUtils.setField(owner, "id", 42L);
		var process = new ReservationProcessEntity(owner, 202, SELECTION.date(), SELECTION.startTime(), "user-42", "alice");
		process.markHeld("hold", "process", TIME.plusSeconds(120), "alice");
		process.claimConfirmation("Ana", "Perez", "alice");
		return process;
	}
}
