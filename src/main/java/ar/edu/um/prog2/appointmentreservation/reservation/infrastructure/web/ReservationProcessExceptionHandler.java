package ar.edu.um.prog2.appointmentreservation.reservation.infrastructure.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import ar.edu.um.prog2.appointmentreservation.availability.application.exception.InvalidAvailabilityScheduleException;
import ar.edu.um.prog2.appointmentreservation.professional.application.exception.ProfessionalLookupException;
import ar.edu.um.prog2.appointmentreservation.reservation.application.exception.HoldRequestException;
import ar.edu.um.prog2.appointmentreservation.reservation.application.exception.InvalidReservationSlotException;
import ar.edu.um.prog2.appointmentreservation.reservation.application.exception.ReservationAuthenticationRequiredException;
import ar.edu.um.prog2.appointmentreservation.reservation.application.exception.ReservationPersistenceException;

@RestControllerAdvice(assignableTypes = ReservationProcessController.class)
public class ReservationProcessExceptionHandler {

	private static final Logger LOG = LoggerFactory.getLogger(ReservationProcessExceptionHandler.class);

	@ExceptionHandler(MethodArgumentNotValidException.class)
	public ResponseEntity<ProblemDetail> invalidFields(MethodArgumentNotValidException failure) {
		var body = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Reservation selection is invalid");
		body.setProperty("code", "VALIDATION_ERROR");
		body.setProperty("fields", failure.getBindingResult().getFieldErrors().stream()
				.map(error -> error.getField()).distinct().sorted().toList());
		return ResponseEntity.badRequest().contentType(MediaType.APPLICATION_PROBLEM_JSON)
				.cacheControl(CacheControl.noStore()).body(body);
	}

	@ExceptionHandler(HttpMessageNotReadableException.class)
	public ResponseEntity<ProblemDetail> invalidJson() {
		return problem(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Reservation request must contain valid JSON and supported fields");
	}

	@ExceptionHandler(ReservationAuthenticationRequiredException.class)
	public ResponseEntity<ProblemDetail> authenticationRequired() {
		var body = ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, "A valid final-user Bearer token is required");
		body.setProperty("code", "UNAUTHORIZED");
		return ResponseEntity.status(HttpStatus.UNAUTHORIZED).contentType(MediaType.APPLICATION_PROBLEM_JSON)
				.cacheControl(CacheControl.noStore()).header(HttpHeaders.WWW_AUTHENTICATE, "Bearer").body(body);
	}

	@ExceptionHandler(InvalidReservationSlotException.class)
	public ResponseEntity<ProblemDetail> invalidSlot() {
		return problem(HttpStatus.BAD_REQUEST, "INVALID_SLOT", "The selected appointment slot is invalid");
	}

	@ExceptionHandler(ProfessionalLookupException.class)
	public ResponseEntity<ProblemDetail> professionalLookup(ProfessionalLookupException failure) {
		LOG.warn("Reservation professional lookup failed ({})", failure.reason());
		return switch (failure.reason()) {
			case NOT_FOUND -> professionalNotFound();
			case DISABLED -> professionalDisabled();
			case CATALOG_TIMEOUT -> problem(HttpStatus.GATEWAY_TIMEOUT, "RESERVATION_DEPENDENCY_TIMEOUT",
					"Current professional information could not be verified in time");
			case CATALOG_UNAVAILABLE -> problem(HttpStatus.SERVICE_UNAVAILABLE, "RESERVATION_DEPENDENCY_UNAVAILABLE",
					"Current professional information is temporarily unavailable");
			case CATALOG_UNAUTHORIZED, CATALOG_FORBIDDEN, INVALID_CATALOG_RESPONSE -> dependencyError();
		};
	}

	@ExceptionHandler(InvalidAvailabilityScheduleException.class)
	public ResponseEntity<ProblemDetail> invalidSchedule() {
		LOG.warn("Reservation current schedule is invalid");
		return dependencyError();
	}

	@ExceptionHandler(HoldRequestException.class)
	public ResponseEntity<ProblemDetail> holdRequest(HoldRequestException failure) {
		LOG.warn("Reservation hold request failed ({})", failure.reason());
		return switch (failure.reason()) {
			case PROFESSIONAL_NOT_FOUND -> professionalNotFound();
			case PROFESSIONAL_DISABLED -> professionalDisabled();
			case INVALID_SLOT -> invalidSlot();
			case SLOT_ALREADY_RESERVED -> problem(HttpStatus.CONFLICT, "SLOT_ALREADY_RESERVED", "The selected slot is already reserved");
			case SLOT_ALREADY_HELD -> problem(HttpStatus.CONFLICT, "SLOT_ALREADY_HELD", "The selected slot is already held");
			case TIMEOUT -> unknownHoldOutcome(HttpStatus.GATEWAY_TIMEOUT);
			case UNAVAILABLE -> unknownHoldOutcome(HttpStatus.SERVICE_UNAVAILABLE);
			case INVALID_RESPONSE -> unknownHoldOutcome(HttpStatus.BAD_GATEWAY);
			case UNAUTHORIZED, FORBIDDEN, REQUEST_REJECTED -> dependencyError();
		};
	}

	@ExceptionHandler(ReservationPersistenceException.class)
	public ResponseEntity<ProblemDetail> persistenceFailed() {
		LOG.error("Reservation process could not be saved locally; remote hold may exist");
		return problem(HttpStatus.INTERNAL_SERVER_ERROR, "RESERVATION_PERSISTENCE_FAILED",
				"The process could not be saved locally; a remote hold may exist. Do not automatically repeat the operation");
	}

	@ExceptionHandler(RuntimeException.class)
	public ResponseEntity<ProblemDetail> unexpected(RuntimeException failure) {
		// Messages, rejected values and causes can contain credentials or raw dependency responses.
		LOG.error("Reservation start failed ({})", failure.getClass().getSimpleName());
		return problem(HttpStatus.INTERNAL_SERVER_ERROR, "RESERVATION_START_FAILED",
				"Reservation start could not be completed; do not automatically repeat the operation");
	}

	private static ResponseEntity<ProblemDetail> professionalNotFound() {
		return problem(HttpStatus.NOT_FOUND, "PROFESSIONAL_NOT_FOUND", "Professional not found");
	}

	private static ResponseEntity<ProblemDetail> professionalDisabled() {
		return problem(HttpStatus.BAD_REQUEST, "PROFESSIONAL_DISABLED", "Professional is disabled");
	}

	private static ResponseEntity<ProblemDetail> dependencyError() {
		return problem(HttpStatus.BAD_GATEWAY, "RESERVATION_DEPENDENCY_ERROR", "A required dependency could not complete reservation start");
	}

	private static ResponseEntity<ProblemDetail> unknownHoldOutcome(HttpStatus status) {
		return problem(status, "HOLD_OUTCOME_UNKNOWN",
				"The hold request outcome is unknown; do not automatically repeat the operation");
	}

	private static ResponseEntity<ProblemDetail> problem(HttpStatus status, String code, String detail) {
		var body = ProblemDetail.forStatusAndDetail(status, detail);
		body.setProperty("code", code);
		return ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON)
				.cacheControl(CacheControl.noStore()).body(body);
	}
}
