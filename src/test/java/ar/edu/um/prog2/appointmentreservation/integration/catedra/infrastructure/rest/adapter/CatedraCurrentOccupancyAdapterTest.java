package ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.rest.adapter;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatusCode;

import ar.edu.um.prog2.appointmentreservation.availability.application.exception.OccupancyLookupException;
import ar.edu.um.prog2.appointmentreservation.availability.application.exception.OccupancyLookupException.Reason;
import ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.rest.client.CatedraAppointmentOccupanciesClient;
import ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.rest.dto.CatedraAppointmentOccupanciesResponse;
import ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.rest.dto.CatedraAppointmentOccupanciesResponse.Occupancy;
import ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.rest.dto.CatedraAppointmentOccupanciesResponse.Status;
import ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.rest.exception.CatedraHttpException;
import ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.rest.exception.CatedraProtocolException;
import ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.rest.exception.CatedraRestException;
import ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.rest.exception.CatedraTimeoutException;
import ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.rest.exception.CatedraTransportException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CatedraCurrentOccupancyAdapterTest {

	private static final LocalDate DATE = LocalDate.of(2026, 9, 14);
	@Mock
	private CatedraAppointmentOccupanciesClient client;

	@Test
	void bothStatusesBlockPositionsAndExpiredOrAbsentExpiryDoesNotReleaseHoldsLocally() {
		when(client.getOccupancies(201, DATE, DATE)).thenReturn(new CatedraAppointmentOccupanciesResponse(201, DATE, DATE, List.of(
				new Occupancy(DATE, LocalTime.of(9, 0), Status.CONFIRMED, null),
				new Occupancy(DATE, LocalTime.of(9, 30), Status.HELD, Instant.EPOCH),
				new Occupancy(DATE, LocalTime.of(10, 0), Status.HELD, null),
				new Occupancy(DATE, LocalTime.of(10, 30), Status.HELD, Instant.parse("2099-01-01T00:00:00Z")))));
		var blocked = new CatedraCurrentOccupancyAdapter(client).loadBlockedStartTimes(201, DATE);
		assertThat(blocked).containsExactlyInAnyOrder(LocalTime.of(9, 0), LocalTime.of(9, 30), LocalTime.of(10, 0), LocalTime.of(10, 30));
		assertThatThrownBy(blocked::clear).isInstanceOf(UnsupportedOperationException.class);
		verify(client).getOccupancies(201, DATE, DATE);
		verifyNoMoreInteractions(client);
	}

	@Test
	void anEmptyValidatedResponseProducesAnEmptySetOfBlockedPositions() {
		when(client.getOccupancies(201, DATE, DATE)).thenReturn(new CatedraAppointmentOccupanciesResponse(201, DATE, DATE, List.of()));
		assertThat(new CatedraCurrentOccupancyAdapter(client).loadBlockedStartTimes(201, DATE)).isEmpty();
	}

	@ParameterizedTest
	@MethodSource("failures")
	void translatesTechnicalErrorsWithoutRetainingRemoteCausesOrCodes(CatedraRestException technical, Reason expected) {
		when(client.getOccupancies(201, DATE, DATE)).thenThrow(technical);
		assertThatThrownBy(() -> new CatedraCurrentOccupancyAdapter(client).loadBlockedStartTimes(201, DATE))
				.isInstanceOfSatisfying(OccupancyLookupException.class, failure -> {
					assertThat(failure.reason()).isEqualTo(expected);
					assertThat(failure).hasMessage("Current occupancy lookup failed: " + expected).hasNoCause();
					assertThat(failure.toString()).doesNotContain("test-only-private-code");
				});
		verify(client).getOccupancies(201, DATE, DATE);
		verifyNoMoreInteractions(client);
	}

	static Stream<Arguments> failures() {
		return Stream.of(
				Arguments.of(new CatedraTimeoutException(), Reason.TIMEOUT),
				Arguments.of(new CatedraTransportException(), Reason.UNAVAILABLE),
				Arguments.of(new CatedraProtocolException(), Reason.INVALID_RESPONSE),
				Arguments.of(http(401), Reason.UNAUTHORIZED),
				Arguments.of(http(403), Reason.FORBIDDEN),
				Arguments.of(http(404), Reason.NOT_FOUND),
				Arguments.of(http(409), Reason.CONFLICT),
				Arguments.of(http(400), Reason.REQUEST_REJECTED),
				Arguments.of(http(429), Reason.REQUEST_REJECTED),
				Arguments.of(http(500), Reason.UNAVAILABLE),
				Arguments.of(http(503), Reason.UNAVAILABLE),
				Arguments.of(http(599), Reason.UNAVAILABLE),
				Arguments.of(http(302), Reason.INVALID_RESPONSE),
				Arguments.of(http(600), Reason.INVALID_RESPONSE));
	}

	private static CatedraHttpException http(int status) {
		return new CatedraHttpException(HttpStatusCode.valueOf(status), "test-only-private-code");
	}
}
