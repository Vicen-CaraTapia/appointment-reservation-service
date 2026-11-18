package ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.rest.adapter;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatusCode;

import ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.rest.client.CatedraAppointmentHoldClient;
import ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.rest.dto.CatedraAppointmentHoldRequest;
import ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.rest.dto.CatedraAppointmentHoldResponse;
import ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.rest.exception.CatedraHoldException;
import ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.rest.exception.CatedraHttpException;
import ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.rest.exception.CatedraProtocolException;
import ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.rest.exception.CatedraRestException;
import ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.rest.exception.CatedraTimeoutException;
import ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.rest.exception.CatedraTransportException;
import ar.edu.um.prog2.appointmentreservation.reservation.application.exception.HoldRequestException;
import ar.edu.um.prog2.appointmentreservation.reservation.application.exception.HoldRequestException.Reason;
import ar.edu.um.prog2.appointmentreservation.reservation.application.model.StartReservationCommand;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class CatedraAppointmentHoldAdapterTest {

	private static final StartReservationCommand SELECTION = new StartReservationCommand(Long.MAX_VALUE,
			LocalDate.of(2026, 11, 19), LocalTime.of(18, 0));
	private static final String PRIVATE_CODE = "private-untrusted-code";
	private final CatedraAppointmentHoldClient client = mock(CatedraAppointmentHoldClient.class);
	private final CatedraAppointmentHoldAdapter adapter = new CatedraAppointmentHoldAdapter(client);

	@Test
	void mapsTheApplicationSelectionAndPreservesOpaqueIdentifiersAndNanosecondExpiry() {
		String holdId = "opaque-hold-" + "x".repeat(300);
		String processId = "opaque-process-" + "y".repeat(300);
		Instant expiry = Instant.parse("2026-11-17T22:28:18.514830573Z");
		when(client.createHold(any())).thenReturn(new CatedraAppointmentHoldResponse(holdId, processId, "HELD", expiry));
		var result = adapter.createHold(SELECTION, "user-42");
		assertThat(result.holdId()).isEqualTo(holdId);
		assertThat(result.reservationProcessId()).isEqualTo(processId);
		assertThat(result.expiresAt()).isEqualTo(expiry);
		verify(client).createHold(new CatedraAppointmentHoldRequest(SELECTION.professionalId(), SELECTION.date(),
				SELECTION.startTime(), "user-42"));
		verifyNoMoreInteractions(client);
	}

	@ParameterizedTest
	@EnumSource(CatedraHoldException.Reason.class)
	void preservesEachValidatedContractualRejection(CatedraHoldException.Reason rejection) {
		when(client.createHold(any())).thenThrow(new CatedraHoldException(rejection));
		assertFailure(Reason.valueOf(rejection.name()));
	}

	@ParameterizedTest
	@MethodSource("technicalFailures")
	void translatesTechnicalErrorsWithoutRetainingRemoteCodesOrCauses(CatedraRestException technical, Reason expected) {
		when(client.createHold(any())).thenThrow(technical);
		assertFailure(expected);
	}

	@ParameterizedTest
	@ValueSource(ints = {400, 404, 409})
	void anHttpStatusAloneDoesNotInventAValidatedBusinessRejection(int status) {
		when(client.createHold(any())).thenThrow(new CatedraHttpException(HttpStatusCode.valueOf(status), "INVALID_SLOT"));
		assertFailure(Reason.REQUEST_REJECTED);
	}

	@Test
	void rejectsMissingSelectionOrInvalidPatientIdentityBeforeCallingTheClient() {
		assertThatThrownBy(() -> adapter.createHold(null, "user-42")).isInstanceOf(NullPointerException.class);
		for (String invalid : new String[] {null, "", " ", "x".repeat(101)}) {
			assertThatThrownBy(() -> adapter.createHold(SELECTION, invalid)).isInstanceOf(IllegalArgumentException.class);
		}
		verifyNoInteractions(client);
	}

	private void assertFailure(Reason expected) {
		assertThatThrownBy(() -> adapter.createHold(SELECTION, "user-42"))
				.isInstanceOfSatisfying(HoldRequestException.class, failure -> {
					assertThat(failure.reason()).isEqualTo(expected);
					assertThat(failure).hasMessage("Appointment hold request failed: " + expected).hasNoCause();
					assertThat(failure.toString()).doesNotContain(PRIVATE_CODE);
				});
		verify(client).createHold(any());
		verifyNoMoreInteractions(client);
	}

	static Stream<Arguments> technicalFailures() {
		return Stream.of(
				Arguments.of(new CatedraTimeoutException(), Reason.TIMEOUT),
				Arguments.of(new CatedraTransportException(), Reason.UNAVAILABLE),
				Arguments.of(new CatedraProtocolException(), Reason.INVALID_RESPONSE),
				Arguments.of(http(401), Reason.UNAUTHORIZED),
				Arguments.of(http(403), Reason.FORBIDDEN),
				Arguments.of(http(400), Reason.REQUEST_REJECTED),
				Arguments.of(http(404), Reason.REQUEST_REJECTED),
				Arguments.of(http(409), Reason.REQUEST_REJECTED),
				Arguments.of(http(429), Reason.REQUEST_REJECTED),
				Arguments.of(http(500), Reason.UNAVAILABLE),
				Arguments.of(http(503), Reason.UNAVAILABLE),
				Arguments.of(http(599), Reason.UNAVAILABLE),
				Arguments.of(http(302), Reason.INVALID_RESPONSE),
				Arguments.of(http(600), Reason.INVALID_RESPONSE));
	}

	private static CatedraHttpException http(int status) {
		return new CatedraHttpException(HttpStatusCode.valueOf(status), PRIVATE_CODE);
	}
}
