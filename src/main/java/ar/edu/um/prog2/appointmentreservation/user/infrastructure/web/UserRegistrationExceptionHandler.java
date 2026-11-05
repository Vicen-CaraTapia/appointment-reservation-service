package ar.edu.um.prog2.appointmentreservation.user.infrastructure.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import ar.edu.um.prog2.appointmentreservation.user.application.exception.DuplicateUserException;
import ar.edu.um.prog2.appointmentreservation.user.application.exception.DuplicateUserException.Field;

@RestControllerAdvice(assignableTypes = UserRegistrationController.class)
public class UserRegistrationExceptionHandler {

	private static final Logger LOG = LoggerFactory.getLogger(UserRegistrationExceptionHandler.class);

	@ExceptionHandler(MethodArgumentNotValidException.class)
	public ResponseEntity<ProblemDetail> invalidFields(MethodArgumentNotValidException failure) {
		ProblemDetail body = problem(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Registration data is invalid");
		body.setProperty("fields", failure.getBindingResult().getFieldErrors().stream()
				.map(error -> error.getField()).distinct().sorted().toList());
		return ResponseEntity.badRequest().body(body);
	}

	@ExceptionHandler(HttpMessageNotReadableException.class)
	public ResponseEntity<ProblemDetail> invalidJson() {
		return ResponseEntity.badRequest().body(problem(HttpStatus.BAD_REQUEST, "INVALID_REQUEST",
				"Registration request must contain valid JSON and supported fields"));
	}

	@ExceptionHandler(DuplicateUserException.class)
	public ResponseEntity<ProblemDetail> duplicate(DuplicateUserException failure) {
		boolean login = failure.field() == Field.LOGIN;
		return ResponseEntity.badRequest().body(problem(HttpStatus.BAD_REQUEST,
				login ? "LOGIN_ALREADY_EXISTS" : "EMAIL_ALREADY_EXISTS",
				login ? "Login already exists" : "Email already exists"));
	}

	@ExceptionHandler(RuntimeException.class)
	public ResponseEntity<ProblemDetail> unexpected(RuntimeException failure) {
		// Messages and causes may contain SQL parameters or credentials. Log only the type.
		LOG.error("Registration failed ({})", failure.getClass().getSimpleName());
		return ResponseEntity.internalServerError().body(problem(HttpStatus.INTERNAL_SERVER_ERROR,
				"REGISTRATION_FAILED", "Registration could not be completed"));
	}

	private static ProblemDetail problem(HttpStatus status, String code, String detail) {
		ProblemDetail body = ProblemDetail.forStatusAndDetail(status, detail);
		body.setProperty("code", code);
		return body;
	}
}
