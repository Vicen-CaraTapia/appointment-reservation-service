package ar.edu.um.prog2.appointmentreservation.reservation.infrastructure.web;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import ar.edu.um.prog2.appointmentreservation.reservation.application.exception.OwnedReservationQueryException;
import ar.edu.um.prog2.appointmentreservation.reservation.application.exception.ReservationAuthenticationRequiredException;
import ar.edu.um.prog2.appointmentreservation.reservation.application.exception.ReservationListingException;

@RestControllerAdvice(assignableTypes = OwnedReservationsController.class)
public class OwnedReservationsExceptionHandler {
	@ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,
			IllegalArgumentException.class})
	public ResponseEntity<ProblemDetail> invalidRequest() {
		return problem(HttpStatus.BAD_REQUEST, "INVALID_REQUEST",
				"Reservation filters, page and size must use supported values");
	}

	@ExceptionHandler(ReservationAuthenticationRequiredException.class)
	public ResponseEntity<ProblemDetail> unauthorized() {
		return ResponseEntity.status(HttpStatus.UNAUTHORIZED).contentType(MediaType.APPLICATION_PROBLEM_JSON)
				.cacheControl(CacheControl.noStore()).header(HttpHeaders.WWW_AUTHENTICATE, "Bearer")
				.body(body(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED",
						"A valid final-user Bearer token is required"));
	}

	@ExceptionHandler(ReservationListingException.class)
	public ResponseEntity<ProblemDetail> catedra(ReservationListingException failure) {
		return switch (failure.reason()) {
			case INVALID_DATE_RANGE -> problem(HttpStatus.BAD_REQUEST, "INVALID_RESERVATION_DATE_RANGE",
					"The reservation date range is invalid");
			case TIMEOUT, UNAVAILABLE -> problem(HttpStatus.SERVICE_UNAVAILABLE, "RESERVATION_LIST_UNAVAILABLE",
					"Reservations are temporarily unavailable");
			case INVALID_RESPONSE -> problem(HttpStatus.BAD_GATEWAY, "INVALID_CATEDRA_RESERVATION_RESPONSE",
					"The reservation service returned an invalid response");
			case INTEGRATION_NOT_FOUND, UNAUTHORIZED, FORBIDDEN, NOT_FOUND, CONFLICT, REQUEST_REJECTED ->
				problem(HttpStatus.BAD_GATEWAY, "CATEDRA_RESERVATION_LIST_REJECTED",
						"The reservation service could not complete the query");
		};
	}

	@ExceptionHandler(OwnedReservationQueryException.class)
	public ResponseEntity<ProblemDetail> consistency(OwnedReservationQueryException failure) {
		return switch (failure.reason()) {
			case INVALID_RESPONSE -> problem(HttpStatus.BAD_GATEWAY, "INVALID_CATEDRA_RESERVATION_RESPONSE",
					"The reservation service returned an invalid response");
			case INCOMPLETE_RESULT -> problem(HttpStatus.SERVICE_UNAVAILABLE, "RESERVATION_LIST_INCOMPLETE",
					"A complete reservation list is temporarily unavailable");
			case CONCURRENT_CHANGE -> problem(HttpStatus.SERVICE_UNAVAILABLE, "RESERVATION_STATE_CHANGED",
					"Reservation state changed during the query; retry the query");
			case CONFLICTING_ASSOCIATION, CONFLICTING_STATE -> problem(HttpStatus.CONFLICT,
					"RESERVATION_STATE_CONFLICT", "Reservation ownership or state could not be verified");
		};
	}

	@ExceptionHandler(RuntimeException.class)
	public ResponseEntity<ProblemDetail> unavailable() {
		return problem(HttpStatus.SERVICE_UNAVAILABLE, "RESERVATION_LIST_UNAVAILABLE",
				"Reservations are temporarily unavailable");
	}

	private static ResponseEntity<ProblemDetail> problem(HttpStatus status, String code, String detail) {
		return ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON)
				.cacheControl(CacheControl.noStore()).body(body(status, code, detail));
	}

	private static ProblemDetail body(HttpStatus status, String code, String detail) {
		var body = ProblemDetail.forStatusAndDetail(status, detail);
		body.setProperty("code", code);
		return body;
	}
}
