package ar.edu.um.prog2.appointmentreservation.reservation.infrastructure.web;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;

import ar.edu.um.prog2.appointmentreservation.reservation.application.exception.ReservationAuthenticationRequiredException;
import ar.edu.um.prog2.appointmentreservation.reservation.application.exception.ReservationCancellationException;
import ar.edu.um.prog2.appointmentreservation.reservation.application.exception.ReservationProcessStateException;

import static org.assertj.core.api.Assertions.assertThat;

class ReservationProcessCancellationExceptionHandlerTest {

	private final ReservationProcessCancellationExceptionHandler handler = new ReservationProcessCancellationExceptionHandler();

	@ParameterizedTest
	@CsvSource({
			"NOT_FOUND,404,RESERVATION_PROCESS_NOT_FOUND",
			"CONFLICT,409,RESERVATION_NOT_CANCELLABLE",
			"EXPIRED,409,RESERVATION_NOT_CANCELLABLE",
			"STORAGE_FAILURE,503,CANCELLATION_OUTCOME_UNKNOWN"
	})
	void mapsLocalProcessFailuresWithoutDetails(String reason, int expectedStatus, String expectedCode) {
		var response = handler.local(new ReservationProcessStateException(ReservationProcessStateException.Reason.valueOf(reason)));
		assertProblem(response.getStatusCode(), response.getBody(), expectedStatus, expectedCode);
	}

	@ParameterizedTest
	@CsvSource({
			"RESERVATION_NOT_CANCELLABLE,409,RESERVATION_NOT_CANCELLABLE",
			"CONFLICT,409,RESERVATION_NOT_CANCELLABLE",
			"RESERVATION_NOT_FOUND,502,CATEDRA_RESERVATION_NOT_FOUND",
			"NOT_FOUND,502,CATEDRA_RESERVATION_NOT_FOUND",
			"RESERVATION_OWNER_MISMATCH,502,CATEDRA_OWNER_MISMATCH",
			"UNAUTHORIZED,502,CATEDRA_UNAUTHORIZED",
			"FORBIDDEN,502,CATEDRA_FORBIDDEN",
			"INTEGRATION_NOT_FOUND,502,CATEDRA_FORBIDDEN",
			"REQUEST_REJECTED,502,CATEDRA_REQUEST_REJECTED",
			"TIMEOUT,503,CANCELLATION_OUTCOME_UNKNOWN",
			"UNAVAILABLE,503,CANCELLATION_OUTCOME_UNKNOWN",
			"REMOTE_FAILURE,503,CANCELLATION_OUTCOME_UNKNOWN",
			"INVALID_RESPONSE,503,CANCELLATION_OUTCOME_UNKNOWN",
			"OUTCOME_UNKNOWN,503,CANCELLATION_OUTCOME_UNKNOWN"
	})
	void mapsTechnicalFailuresWithoutLeakingTheirMessages(String reason, int expectedStatus, String expectedCode) {
		var response = handler.remote(new ReservationCancellationException(ReservationCancellationException.Reason.valueOf(reason)));
		assertProblem(response.getStatusCode(), response.getBody(), expectedStatus, expectedCode);
	}

	@Test
	void mapsApplicationAuthenticationFailureToFinalUserUnauthorized() {
		var response = handler.unauthorized();
		assertProblem(response.getStatusCode(), response.getBody(), 401, "UNAUTHORIZED");
		assertThat(response.getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE)).isEqualTo("Bearer");
	}

	@Test
	void mapsUnexpectedFailuresToSanitizedUnavailableResponse() {
		var response = handler.unknown();
		assertProblem(response.getStatusCode(), response.getBody(), 503, "CANCELLATION_OUTCOME_UNKNOWN");
	}

	private static void assertProblem(HttpStatusCode status, org.springframework.http.ProblemDetail body,
			int expectedStatus, String expectedCode) {
		assertThat(status.value()).isEqualTo(expectedStatus);
		assertThat(body).isNotNull();
		assertThat(body.getStatus()).isEqualTo(expectedStatus);
		assertThat(body.getProperties()).containsEntry("code", expectedCode);
		assertThat(body.getDetail()).doesNotContain("diagnostic", "token", "password");
	}
}
