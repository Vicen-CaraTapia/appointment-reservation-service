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

import ar.edu.um.prog2.appointmentreservation.user.application.exception.AuthenticationUnavailableException;
import ar.edu.um.prog2.appointmentreservation.user.application.exception.InvalidCredentialsException;

@RestControllerAdvice(assignableTypes = UserAuthenticationController.class)
public class UserAuthenticationExceptionHandler {
	private static final Logger LOG = LoggerFactory.getLogger(UserAuthenticationExceptionHandler.class);

	@ExceptionHandler(InvalidCredentialsException.class)
	public ResponseEntity<ProblemDetail> invalidCredentials() {
		return problem(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "Invalid credentials");
	}

	@ExceptionHandler(MethodArgumentNotValidException.class)
	public ResponseEntity<ProblemDetail> invalidFields(MethodArgumentNotValidException failure) {
		var response = problem(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Authentication data is invalid");
		response.getBody().setProperty("fields", failure.getBindingResult().getFieldErrors().stream()
				.map(error -> error.getField()).distinct().sorted().toList());
		return response;
	}

	@ExceptionHandler(HttpMessageNotReadableException.class)
	public ResponseEntity<ProblemDetail> invalidJson() {
		return problem(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Authentication request must contain valid JSON and supported fields");
	}

	@ExceptionHandler(AuthenticationUnavailableException.class)
	public ResponseEntity<ProblemDetail> unavailable() {
		LOG.error("Authentication service unavailable");
		return problem(HttpStatus.SERVICE_UNAVAILABLE, "AUTHENTICATION_UNAVAILABLE", "Authentication temporarily unavailable");
	}

	@ExceptionHandler(RuntimeException.class)
	public ResponseEntity<ProblemDetail> unexpected(RuntimeException failure) {
		LOG.error("Authentication failed ({})", failure.getClass().getSimpleName());
		return problem(HttpStatus.INTERNAL_SERVER_ERROR, "AUTHENTICATION_FAILED", "Authentication could not be completed");
	}

	private static ResponseEntity<ProblemDetail> problem(HttpStatus status, String code, String detail) {
		ProblemDetail body = ProblemDetail.forStatusAndDetail(status, detail);
		body.setProperty("code", code);
		return ResponseEntity.status(status).body(body);
	}
}
