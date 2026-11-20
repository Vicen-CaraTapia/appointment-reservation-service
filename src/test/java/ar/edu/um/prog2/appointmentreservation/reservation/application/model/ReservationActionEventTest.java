package ar.edu.um.prog2.appointmentreservation.reservation.application.model;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import ar.edu.um.prog2.appointmentreservation.reservation.domain.model.AsynchronousReservationTransitions.Signal;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReservationActionEventTest {
	private static final Instant TIME = Instant.parse("2026-11-20T12:00:00.123456789Z");
	private static final StartReservationCommand SELECTION = new StartReservationCommand(202, LocalDate.of(2030, 1, 3), LocalTime.of(18, 0));

	@Test
	void preservesOpaqueIdsNanosecondEvidenceAndSeparateRequestIdentityWithoutLeakingValues() {
		var request = new ReservationActionEvent("event/opaque", Signal.PHONE_REQUESTED, "process/opaque", "hold/opaque",
				null, TIME, TIME.plusSeconds(300), null, null, null);
		assertThat(request.eventId()).isEqualTo("event/opaque");
		assertThat(request.occurredAt()).isEqualTo(TIME);
		assertThat(request.toString()).isEqualTo("ReservationActionEvent[redacted]");
		assertThat(new ReservationEventReceipt("event/opaque", ReservationEventReceipt.State.PENDING, null, false).toString())
				.isEqualTo("ReservationEventReceipt[redacted]");
	}

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = {" ", "\t"})
	void identifiersAreRequiredAndNeverEchoed(String missing) {
		assertThatThrownBy(() -> new ReservationActionEvent(missing, Signal.PHONE_REQUESTED, "process", "hold", null,
				TIME, TIME, null, null, null)).isInstanceOf(IllegalArgumentException.class)
				.hasMessage("Reservation event identifiers are required");
	}

	@ParameterizedTest
	@MethodSource("incompatibleEvidence")
	void invalidEvidenceCannotReachPersistence(Signal signal, String requestId, Instant expiry, Long reservationId,
			StartReservationCommand selection, String reason) {
		assertThatThrownBy(() -> new ReservationActionEvent("event", signal, "process", "hold", requestId,
				TIME, expiry, reservationId, selection, reason)).isInstanceOf(IllegalArgumentException.class)
				.hasMessage("Reservation event evidence is incomplete or incompatible");
	}

	static Stream<Arguments> incompatibleEvidence() {
		return Stream.of(
				Arguments.of(Signal.PHONE_REQUESTED, null, null, null, null, null),
				Arguments.of(Signal.PHONE_REQUESTED, "request", TIME, null, null, null),
				Arguments.of(Signal.PHONE_REQUESTED, null, TIME, 1L, null, null),
				Arguments.of(Signal.PHONE_SUBMISSION_ACCEPTED, null, TIME, null, null, null),
				Arguments.of(Signal.PHONE_PUBLICATION_ACKNOWLEDGED, null, TIME, null, null, null),
				Arguments.of(Signal.RESERVATION_CONFIRMED, null, null, 1L, SELECTION, null),
				Arguments.of(Signal.RESERVATION_CONFIRMED, "request", null, 0L, SELECTION, null),
				Arguments.of(Signal.RESERVATION_CONFIRMED, "request", null, 1L, null, null),
				Arguments.of(Signal.PHONE_REJECTED, "request", null, null, null, "INVALID_PHONE_NUMBER"),
				Arguments.of(Signal.PHONE_REJECTED, "request", TIME, null, null, "private human diagnostic"),
				Arguments.of(Signal.PROCESS_EXPIRED, "request", null, null, null, null),
				Arguments.of(Signal.PROCESS_INVALID, "request", TIME, null, null, "REQUEST_EVENT_MISMATCH"));
	}

	@Test
	void eachAuthoritativeResultHasItsOwnRequiredEvidence() {
		assertThat(new ReservationActionEvent("confirmed", Signal.RESERVATION_CONFIRMED, "process", "hold", "request", TIME,
				null, Long.MAX_VALUE, SELECTION, null).externalReservationId()).isEqualTo(Long.MAX_VALUE);
		assertThat(new ReservationActionEvent("rejected", Signal.PHONE_REJECTED, "process", "hold", "request", TIME,
				TIME.plusSeconds(300), null, null, "INVALID_PHONE_NUMBER").reason()).isEqualTo("INVALID_PHONE_NUMBER");
		assertThat(new ReservationActionEvent("expired", Signal.PROCESS_EXPIRED, "process", "hold", "request", TIME,
				null, null, null, "HOLD_EXPIRED").signal()).isEqualTo(Signal.PROCESS_EXPIRED);
		assertThat(new ReservationActionEvent("invalid", Signal.PROCESS_INVALID, "process", "hold", "request", TIME,
				null, null, null, "REQUEST_EVENT_MISMATCH").signal()).isEqualTo(Signal.PROCESS_INVALID);
	}
}
