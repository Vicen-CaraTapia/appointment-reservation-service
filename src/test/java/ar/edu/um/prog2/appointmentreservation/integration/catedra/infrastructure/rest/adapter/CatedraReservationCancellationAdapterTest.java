package ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.rest.adapter;

import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.rest.client.CatedraReservationCancellationClient;
import ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.rest.exception.CatedraRestException;
import ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.rest.exception.CatedraTimeoutException;
import ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.rest.exception.CatedraTransportException;
import ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.rest.exception.CatedraProtocolException;
import ar.edu.um.prog2.appointmentreservation.reservation.application.exception.ReservationCancellationException;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class CatedraReservationCancellationAdapterTest {
	@ParameterizedTest
	@MethodSource("uncertainFailures")
	void preservesFailureCategoryWithoutRetryOrExposingInput(CatedraRestException failure, String expected) {
		var client = mock(CatedraReservationCancellationClient.class);
		when(client.cancel("private-process", "private reason")).thenThrow(failure);
		assertThatThrownBy(() -> new CatedraReservationCancellationAdapter(client).cancel("private-process", "private reason"))
				.isInstanceOfSatisfying(ReservationCancellationException.class,
						error -> assertThat(error.reason().name()).isEqualTo(expected))
				.hasNoCause().hasMessageNotContaining("private");
		verify(client).cancel("private-process", "private reason");
		verifyNoMoreInteractions(client);
	}

	static List<Arguments> uncertainFailures() {
		return List.of(Arguments.of(new CatedraTimeoutException(), "TIMEOUT"),
				Arguments.of(new CatedraTransportException(), "UNAVAILABLE"),
				Arguments.of(new CatedraProtocolException(), "INVALID_RESPONSE"));
	}
}
