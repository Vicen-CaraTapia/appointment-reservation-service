package ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.kafka.event;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.kafka.exception.CatedraActionProcessingException;
import ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.kafka.exception.CatedraActionStorageException;
import ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.kafka.exception.InvalidCatedraActionException.Reason;
import ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.kafka.persistence.RejectedCatedraActionStore;
import ar.edu.um.prog2.appointmentreservation.reservation.application.model.ReservationEventReceipt;
import ar.edu.um.prog2.appointmentreservation.reservation.application.model.ReservationEventReceipt.State;
import ar.edu.um.prog2.appointmentreservation.reservation.application.usecase.ProcessReservationActionUseCase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class CatedraAppointmentActionListenerTest {
	private static final String TOPIC = "test.appointment.actions";
	private static final String KEY = "process-12";
	private static final String PAYLOAD = """
			{"schemaVersion":1,"eventId":"request-1","eventType":"AdditionalInformationRequested",
			"reservationProcessId":"process-12","holdId":"hold-7","requiredField":"PHONE_NUMBER",
			"message":"Provide a phone number","expiresAt":"2026-11-20T16:30:00Z",
			"occurredAt":"2026-11-20T16:00:00Z"}
			""";
	private final ProcessReservationActionUseCase actions = mock(ProcessReservationActionUseCase.class);
	private final RejectedCatedraActionStore rejected = mock(RejectedCatedraActionStore.class);
	private final CatedraAppointmentActionListener listener = new CatedraAppointmentActionListener(
			new CatedraAppointmentActionDecoder("test-project"), actions, rejected);

	@BeforeEach
	void successfulActionReturnsDurableReceipt() {
		when(actions.process(any())).thenReturn(new ReservationEventReceipt("request-1", State.APPLIED, 42L, false));
	}

	@Test
	void validRecordIsDecodedAndPassedToTheApplicationUseCase() {
		listener.receive(record(KEY, PAYLOAD));

		var event = org.mockito.ArgumentCaptor.forClass(
				ar.edu.um.prog2.appointmentreservation.reservation.application.model.ReservationActionEvent.class);
		verify(actions).process(event.capture());
		assertThat(event.getValue().eventId()).isEqualTo("request-1");
		assertThat(event.getValue().externalProcessId()).isEqualTo(KEY);
		verifyNoInteractions(rejected);
	}

	@Test
	void invalidRecordGetsADurableSanitizedReceiptBeforeTheListenerReturns() {
		String privateInput = "sensitive-invalid-kafka-value";
		var record = record(KEY, privateInput);

		listener.receive(record);

		verify(rejected).record(TOPIC, 2, 17L, KEY, privateInput, Reason.INVALID_PAYLOAD);
		verifyNoInteractions(actions);
	}

	@Test
	void failureToPersistARejectionStopsProcessingWithoutExposingTheRecord() {
		String privateInput = "sensitive-invalid-kafka-value";
		doThrow(new CatedraActionStorageException()).when(rejected).record(any(),
				org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyLong(), any(), any(), any());

		assertThatThrownBy(() -> listener.receive(record(KEY, privateInput)))
				.isInstanceOfSatisfying(CatedraActionProcessingException.class, failure -> {
					assertThat(failure).hasNoCause();
					assertThat(failure).hasMessage("Catedra appointment action could not be processed");
					assertThat(failure.toString()).doesNotContain(privateInput);
				});
		verifyNoInteractions(actions);
	}

	@Test
	void failureToPersistAValidEventIsAlsoSanitizedForTheContainerErrorHandler() {
		String privateDiagnostic = "private-persistence-diagnostic";
		when(actions.process(any())).thenThrow(new IllegalStateException(privateDiagnostic));

		assertThatThrownBy(() -> listener.receive(record(KEY, PAYLOAD)))
				.isInstanceOfSatisfying(CatedraActionProcessingException.class, failure -> {
					assertThat(failure).hasNoCause();
					assertThat(failure.toString()).doesNotContain(privateDiagnostic, PAYLOAD, KEY);
				});
		verifyNoInteractions(rejected);
	}

	private static ConsumerRecord<String, String> record(String key, String payload) {
		return new ConsumerRecord<>(TOPIC, 2, 17L, key, payload);
	}
}
