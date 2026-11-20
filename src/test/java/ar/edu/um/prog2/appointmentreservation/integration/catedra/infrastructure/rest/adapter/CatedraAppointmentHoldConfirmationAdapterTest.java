package ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.rest.adapter;

import java.time.Instant;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatusCode;

import ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.rest.client.CatedraAppointmentHoldConfirmationClient;
import ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.rest.dto.CatedraAppointmentHoldConfirmationRequest;
import ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.rest.dto.CatedraAppointmentHoldConfirmationResponse;
import ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.rest.exception.CatedraHoldConfirmationException;
import ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.rest.exception.CatedraHttpException;
import ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.rest.exception.CatedraProtocolException;
import ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.rest.exception.CatedraRestException;
import ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.rest.exception.CatedraTimeoutException;
import ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.rest.exception.CatedraTransportException;
import ar.edu.um.prog2.appointmentreservation.reservation.application.exception.InitialConfirmationException;
import ar.edu.um.prog2.appointmentreservation.reservation.application.exception.InitialConfirmationException.Reason;
import ar.edu.um.prog2.appointmentreservation.reservation.application.model.AppointmentHold;
import ar.edu.um.prog2.appointmentreservation.reservation.application.model.ConfirmationPatient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class CatedraAppointmentHoldConfirmationAdapterTest {

	private static final AppointmentHold HOLD = new AppointmentHold("hold/opaque", "process/opaque", Instant.EPOCH);
	private static final ConfirmationPatient PATIENT = new ConfirmationPatient(" Ana ", " Pérez ");
	private static final String PRIVATE_CODE = "private-untrusted-code";
	private final CatedraAppointmentHoldConfirmationClient client = mock(CatedraAppointmentHoldConfirmationClient.class);
	private final CatedraAppointmentHoldConfirmationAdapter adapter = new CatedraAppointmentHoldConfirmationAdapter(client);

	@Test
	void mapsTrustedIdentifiersAndPatientInputAndPreservesTheAuthoritativeExpiry() {
		var expiry = Instant.parse("2026-11-19T14:00:00.123456789Z");
		when(client.confirm(eq(HOLD.holdId()), eq(HOLD.reservationProcessId()), any()))
				.thenReturn(new CatedraAppointmentHoldConfirmationResponse(HOLD.reservationProcessId(), "WAITING_FOR_PHONE", expiry));
		var result = adapter.confirm(HOLD, "user-42", PATIENT);
		assertThat(result.reservationProcessId()).isEqualTo(HOLD.reservationProcessId());
		assertThat(result.patientFirstName()).isEqualTo(PATIENT.firstName());
		assertThat(result.patientLastName()).isEqualTo(PATIENT.lastName());
		assertThat(result.expiresAt()).isEqualTo(expiry);
		verify(client).confirm(HOLD.holdId(), HOLD.reservationProcessId(),
				new CatedraAppointmentHoldConfirmationRequest("user-42", PATIENT.firstName(), PATIENT.lastName()));
		verifyNoMoreInteractions(client);
	}

	@ParameterizedTest
	@EnumSource(CatedraHoldConfirmationException.Reason.class)
	void preservesEachValidatedContractualRejection(CatedraHoldConfirmationException.Reason rejection) {
		when(client.confirm(any(), any(), any())).thenThrow(new CatedraHoldConfirmationException(rejection));
		assertFailure(Reason.valueOf(rejection.name()));
	}

	@ParameterizedTest
	@MethodSource("technicalFailures")
	void translatesTechnicalFailuresWithoutRetainingCausesOrRemoteCodes(CatedraRestException failure, Reason expected) {
		when(client.confirm(any(), any(), any())).thenThrow(failure);
		assertFailure(expected);
	}

	@ParameterizedTest
	@ValueSource(ints = {400, 404, 409})
	void anUnvalidatedHttpCodeDoesNotInventAFunctionalRejection(int status) {
		when(client.confirm(any(), any(), any())).thenThrow(new CatedraHttpException(HttpStatusCode.valueOf(status), "HOLD_EXPIRED"));
		assertFailure(Reason.REQUEST_REJECTED);
	}

	@Test
	void anAbsentResponseCannotBecomeAnAcceptance() {
		assertFailure(Reason.INVALID_RESPONSE);
	}

	@Test
	void invalidLocalInputDoesNotCallTheTechnicalClient() {
		assertThatThrownBy(() -> adapter.confirm(null, "user-42", PATIENT)).isInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> adapter.confirm(HOLD, "user-42", null)).isInstanceOf(NullPointerException.class);
		for (String id : new String[] {null, "", " ", "p".repeat(101)}) {
			assertThatThrownBy(() -> adapter.confirm(HOLD, id, PATIENT)).isInstanceOf(IllegalArgumentException.class);
		}
		verifyNoInteractions(client);
	}

	private void assertFailure(Reason expected) {
		assertThatThrownBy(() -> adapter.confirm(HOLD, "user-42", PATIENT))
				.isInstanceOfSatisfying(InitialConfirmationException.class, failure -> {
					assertThat(failure.reason()).isEqualTo(expected);
					assertThat(failure).hasNoCause().hasMessage("Initial hold confirmation failed: " + expected);
					assertThat(failure.toString()).doesNotContain(PRIVATE_CODE, PATIENT.firstName(), PATIENT.lastName());
				});
		verify(client).confirm(eq(HOLD.holdId()), eq(HOLD.reservationProcessId()), any());
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
