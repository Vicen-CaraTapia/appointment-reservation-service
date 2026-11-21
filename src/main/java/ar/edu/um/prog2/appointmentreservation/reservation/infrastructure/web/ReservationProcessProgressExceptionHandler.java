package ar.edu.um.prog2.appointmentreservation.reservation.infrastructure.web;

import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import ar.edu.um.prog2.appointmentreservation.reservation.application.exception.ReservationProcessStateException;
import ar.edu.um.prog2.appointmentreservation.reservation.application.exception.ReservationAuthenticationRequiredException;

@RestControllerAdvice(assignableTypes = ReservationProcessProgressController.class)
public class ReservationProcessProgressExceptionHandler {
	@ExceptionHandler({HandlerMethodValidationException.class, MethodArgumentTypeMismatchException.class})
	public ResponseEntity<ProblemDetail> invalidRequest() {
		return problem(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "A positive local process ID is required");
	}
	@ExceptionHandler(ReservationAuthenticationRequiredException.class)
	public ResponseEntity<ProblemDetail> unauthorized() {
		return ResponseEntity.status(HttpStatus.UNAUTHORIZED).contentType(MediaType.APPLICATION_PROBLEM_JSON)
				.cacheControl(CacheControl.noStore()).header(HttpHeaders.WWW_AUTHENTICATE, "Bearer")
				.body(body(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "A valid final-user Bearer token is required"));
	}
	@ExceptionHandler(ReservationProcessStateException.class)
	public ResponseEntity<ProblemDetail> storage(ReservationProcessStateException failure) {
		if (failure.reason() == ReservationProcessStateException.Reason.NOT_FOUND) {
			return problem(HttpStatus.NOT_FOUND, "RESERVATION_PROCESS_NOT_FOUND", "Reservation process not found");
		}
		return unavailable();
	}
	@ExceptionHandler(RuntimeException.class)
	public ResponseEntity<ProblemDetail> unavailable() {
		return problem(HttpStatus.INTERNAL_SERVER_ERROR, "RESERVATION_PERSISTENCE_FAILED", "Reservation progress could not be read");
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
