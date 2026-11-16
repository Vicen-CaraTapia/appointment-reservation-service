package ar.edu.um.prog2.appointmentreservation.availability.infrastructure.web;

import java.time.format.DateTimeParseException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import ar.edu.um.prog2.appointmentreservation.availability.application.exception.InvalidAvailabilityScheduleException;
import ar.edu.um.prog2.appointmentreservation.availability.application.exception.OccupancyLookupException;
import ar.edu.um.prog2.appointmentreservation.professional.application.exception.ProfessionalLookupException;

@RestControllerAdvice(assignableTypes = AppointmentAvailabilityController.class)
public class AppointmentAvailabilityExceptionHandler {

	private static final Logger LOG = LoggerFactory.getLogger(AppointmentAvailabilityExceptionHandler.class);

	@ExceptionHandler({MissingServletRequestParameterException.class, MethodArgumentTypeMismatchException.class,
			DateTimeParseException.class})
	public ResponseEntity<ProblemDetail> invalidParameters() {
		return problem(HttpStatus.BAD_REQUEST, "INVALID_REQUEST",
				"A professionalId int64 and an ISO date are required");
	}

	@ExceptionHandler(ProfessionalLookupException.class)
	public ResponseEntity<ProblemDetail> professionalLookup(ProfessionalLookupException failure) {
		LOG.warn("Availability professional lookup failed ({})", failure.reason());
		return switch (failure.reason()) {
			case NOT_FOUND -> problem(HttpStatus.NOT_FOUND, "PROFESSIONAL_NOT_FOUND", "Professional not found");
			case DISABLED -> problem(HttpStatus.CONFLICT, "PROFESSIONAL_DISABLED", "Professional is disabled");
			case CATALOG_TIMEOUT -> timeout();
			case CATALOG_UNAVAILABLE -> unavailable();
			case CATALOG_UNAUTHORIZED, CATALOG_FORBIDDEN, INVALID_CATALOG_RESPONSE -> dependencyError();
		};
	}

	@ExceptionHandler(OccupancyLookupException.class)
	public ResponseEntity<ProblemDetail> occupancyLookup(OccupancyLookupException failure) {
		LOG.warn("Availability occupancy lookup failed ({})", failure.reason());
		return switch (failure.reason()) {
			case TIMEOUT -> timeout();
			case UNAVAILABLE -> unavailable();
			case UNAUTHORIZED, FORBIDDEN, NOT_FOUND, CONFLICT, REQUEST_REJECTED, INVALID_RESPONSE -> dependencyError();
		};
	}

	@ExceptionHandler(InvalidAvailabilityScheduleException.class)
	public ResponseEntity<ProblemDetail> invalidSchedule() {
		LOG.warn("Availability current schedule is invalid");
		return dependencyError();
	}

	@ExceptionHandler(RuntimeException.class)
	public ResponseEntity<ProblemDetail> unexpected(RuntimeException failure) {
		// Messages and causes can contain credentials or remote payloads. Only log the type.
		LOG.error("Availability calculation failed ({})", failure.getClass().getSimpleName());
		return problem(HttpStatus.INTERNAL_SERVER_ERROR, "AVAILABILITY_FAILED", "Availability could not be calculated");
	}

	private static ResponseEntity<ProblemDetail> timeout() {
		return problem(HttpStatus.GATEWAY_TIMEOUT, "AVAILABILITY_TIMEOUT", "Availability verification timed out");
	}

	private static ResponseEntity<ProblemDetail> unavailable() {
		return problem(HttpStatus.SERVICE_UNAVAILABLE, "AVAILABILITY_UNAVAILABLE", "Availability is temporarily unavailable");
	}

	private static ResponseEntity<ProblemDetail> dependencyError() {
		return problem(HttpStatus.BAD_GATEWAY, "AVAILABILITY_DEPENDENCY_ERROR", "Availability could not be verified");
	}

	private static ResponseEntity<ProblemDetail> problem(HttpStatus status, String code, String detail) {
		var body = ProblemDetail.forStatusAndDetail(status, detail);
		body.setProperty("code", code);
		return ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON)
				.cacheControl(CacheControl.noStore()).body(body);
	}
}
