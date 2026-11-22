package ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.rest.adapter;

import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.Arguments;
import org.springframework.http.HttpStatusCode;
import ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.rest.client.CatedraReservationListingClient;
import ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.rest.exception.*;
import ar.edu.um.prog2.appointmentreservation.reservation.application.model.ReservationListQuery;
import ar.edu.um.prog2.appointmentreservation.reservation.application.exception.ReservationListingException;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class CatedraTechnicalReservationAdapterTest {
	@ParameterizedTest @MethodSource("failures")
	void sanitizesFailuresWithoutRetry(CatedraRestException failure,String reason) {
		var client=mock(CatedraReservationListingClient.class);
		var query=new ReservationListQuery(null,null,null,null,null,null,List.of());
		when(client.list(query)).thenThrow(failure);
		assertThatThrownBy(()->new CatedraTechnicalReservationAdapter(client).list(query))
				.isInstanceOfSatisfying(ReservationListingException.class,error->assertThat(error.reason().name()).isEqualTo(reason))
				.hasNoCause().hasMessageNotContaining("private");
		verify(client).list(query); verifyNoMoreInteractions(client);
	}
	static List<Arguments> failures() {
		return List.of(Arguments.of(new CatedraTimeoutException(),"TIMEOUT"),
				Arguments.of(new CatedraTransportException(),"UNAVAILABLE"),
				Arguments.of(new CatedraProtocolException(),"INVALID_RESPONSE"),
				Arguments.of(new CatedraHttpException(HttpStatusCode.valueOf(401),null),"UNAUTHORIZED"),
				Arguments.of(new CatedraHttpException(HttpStatusCode.valueOf(403),null),"FORBIDDEN"),
				Arguments.of(new CatedraHttpException(HttpStatusCode.valueOf(400),"private-unknown-code"),"REQUEST_REJECTED"),
				Arguments.of(new CatedraHttpException(HttpStatusCode.valueOf(400),"STUDENT_INTEGRATION_NOT_FOUND"),"REQUEST_REJECTED"));
	}
}
