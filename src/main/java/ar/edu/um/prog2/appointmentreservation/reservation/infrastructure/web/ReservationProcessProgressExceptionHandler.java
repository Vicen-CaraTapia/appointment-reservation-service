package ar.edu.um.prog2.appointmentreservation.reservation.infrastructure.web;

import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import ar.edu.um.prog2.appointmentreservation.reservation.application.exception.InvalidPhoneNumberException;
import ar.edu.um.prog2.appointmentreservation.reservation.application.exception.PhoneSubmissionStateException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import ar.edu.um.prog2.appointmentreservation.reservation.application.exception.ReservationProcessStateException;
import ar.edu.um.prog2.appointmentreservation.reservation.application.exception.ReservationAuthenticationRequiredException;

@RestControllerAdvice(assignableTypes = {ReservationProcessProgressController.class, ReservationProcessPhoneController.class})
public class ReservationProcessProgressExceptionHandler {
	@ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
	public ResponseEntity<ProblemDetail> invalidRequest() {
		return problem(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "A positive local process ID and supported JSON fields are required");
	}
	@ExceptionHandler(HandlerMethodValidationException.class)
	public ResponseEntity<ProblemDetail> invalidParameters(HandlerMethodValidationException failure) {
		return failure.getBeanResults().isEmpty() ? invalidRequest() : invalidPhoneBody();
	}
	@ExceptionHandler(MethodArgumentNotValidException.class)
	public ResponseEntity<ProblemDetail> invalidPhoneBody() {
		return problem(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "A nonblank phone number is required");
	}
	@ExceptionHandler(InvalidPhoneNumberException.class)
	public ResponseEntity<ProblemDetail> invalidPhoneNumber() {
		return problem(HttpStatus.BAD_REQUEST, "INVALID_PHONE_NUMBER", "Phone number must contain 7 to 15 digits and an optional leading plus");
	}
	@ExceptionHandler(PhoneSubmissionStateException.class)
	public ResponseEntity<ProblemDetail> phoneState(PhoneSubmissionStateException failure) {
		return switch (failure.reason()) {
			case NOT_FOUND -> problem(HttpStatus.NOT_FOUND, "RESERVATION_PROCESS_NOT_FOUND", "Reservation process not found");
			case CONFLICT -> problem(HttpStatus.CONFLICT, "PHONE_SUBMISSION_CONFLICT", "The current process does not permit this phone submission");
			case EXPIRED -> problem(HttpStatus.CONFLICT, "RESERVATION_PROCESS_EXPIRED", "The process deadline does not permit a new submission");
			case STORAGE_FAILURE -> problem(HttpStatus.SERVICE_UNAVAILABLE, "PHONE_SUBMISSION_OUTCOME_UNKNOWN",
					"A submission may already be stored; consult process progress before repeating the operation");
		};
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
		return problem(HttpStatus.INTERNAL_SERVER_ERROR, "RESERVATION_PERSISTENCE_FAILED", "The reservation operation could not be completed; consult progress before repeating an operation");
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
