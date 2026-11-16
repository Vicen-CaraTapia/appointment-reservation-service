package ar.edu.um.prog2.appointmentreservation.availability.infrastructure.web;

import java.time.format.DateTimeParseException;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice(assignableTypes = AppointmentAvailabilityController.class)
public class AppointmentAvailabilityExceptionHandler {

	@ExceptionHandler({MissingServletRequestParameterException.class, MethodArgumentTypeMismatchException.class,
			DateTimeParseException.class})
	public ResponseEntity<ProblemDetail> invalidParameters() {
		var body = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
				"A professionalId int64 and an ISO date are required");
		body.setProperty("code", "INVALID_REQUEST");
		return ResponseEntity.badRequest().body(body);
	}
}
