package ar.edu.um.prog2.appointmentreservation.reservation.infrastructure.web;

import java.util.List;

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
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import ar.edu.um.prog2.appointmentreservation.availability.application.exception.InvalidAvailabilityScheduleException;
import ar.edu.um.prog2.appointmentreservation.professional.application.exception.ProfessionalLookupException;
import ar.edu.um.prog2.appointmentreservation.reservation.application.exception.InitialConfirmationException;
import ar.edu.um.prog2.appointmentreservation.reservation.application.exception.InvalidReservationSlotException;
import ar.edu.um.prog2.appointmentreservation.reservation.application.exception.ReservationAuthenticationRequiredException;
import ar.edu.um.prog2.appointmentreservation.reservation.application.exception.ReservationProcessStateException;

@RestControllerAdvice(assignableTypes = ReservationProcessConfirmationController.class)
public class ReservationProcessConfirmationExceptionHandler {

	private static final Logger LOG = LoggerFactory.getLogger(ReservationProcessConfirmationExceptionHandler.class);

	@ExceptionHandler(MethodArgumentNotValidException.class)
	public ResponseEntity<ProblemDetail> invalidFields(MethodArgumentNotValidException failure) {
		return invalidFields(failure.getBindingResult().getFieldErrors().stream()
				.map(error -> error.getField()).distinct().sorted().toList());
	}

	@ExceptionHandler(HandlerMethodValidationException.class)
	public ResponseEntity<ProblemDetail> invalidParameters(HandlerMethodValidationException failure) {
		// MVC method validation also validates the body when a path parameter has constraints.
		if (!failure.getValueResults().isEmpty()) {
			return invalidRequest();
		}
		return invalidFields(failure.getBeanResults().stream().flatMap(errors -> errors.getFieldErrors().stream())
				.map(error -> error.getField()).distinct().sorted().toList());
	}

	private static ResponseEntity<ProblemDetail> invalidFields(List<String> fields) {
		var body = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Confirmation patient fields are invalid");
		body.setProperty("code", "VALIDATION_ERROR");
		body.setProperty("fields", fields);
		return ResponseEntity.badRequest().contentType(MediaType.APPLICATION_PROBLEM_JSON)
				.cacheControl(CacheControl.noStore()).body(body);
	}

	@ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
	public ResponseEntity<ProblemDetail> invalidRequest() {
		return problem(HttpStatus.BAD_REQUEST, "INVALID_REQUEST",
				"Confirmation requires a positive local process ID and valid JSON with supported fields");
	}

	@ExceptionHandler(ReservationAuthenticationRequiredException.class)
	public ResponseEntity<ProblemDetail> authenticationRequired() {
		var body = ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, "A valid final-user Bearer token is required");
		body.setProperty("code", "UNAUTHORIZED");
		return ResponseEntity.status(HttpStatus.UNAUTHORIZED).contentType(MediaType.APPLICATION_PROBLEM_JSON)
				.cacheControl(CacheControl.noStore()).header(HttpHeaders.WWW_AUTHENTICATE, "Bearer").body(body);
	}

	@ExceptionHandler(ReservationProcessStateException.class)
	public ResponseEntity<ProblemDetail> processState(ReservationProcessStateException failure) {
		LOG.warn("Reservation confirmation local state failure ({})", failure.reason());
		return switch (failure.reason()) {
			case NOT_FOUND -> problem(HttpStatus.NOT_FOUND, "RESERVATION_PROCESS_NOT_FOUND", "Reservation process not found");
			case CONFLICT -> problem(HttpStatus.CONFLICT, "RESERVATION_PROCESS_CONFLICT",
					"The current reservation process state or patient does not permit this confirmation");
			case EXPIRED -> holdExpired();
			case STORAGE_FAILURE -> problem(HttpStatus.INTERNAL_SERVER_ERROR, "RESERVATION_PERSISTENCE_FAILED",
					"The process could not be accessed or saved locally; a remote effect may exist. Do not automatically repeat the operation");
		};
	}

	@ExceptionHandler(InitialConfirmationException.class)
	public ResponseEntity<ProblemDetail> confirmation(InitialConfirmationException failure) {
		LOG.warn("Reservation initial confirmation failed ({})", failure.reason());
		return switch (failure.reason()) {
			case PROFESSIONAL_NOT_FOUND -> professionalNotFound();
			case INVALID_SLOT -> invalidSlot();
			case SLOT_ALREADY_RESERVED -> problem(HttpStatus.CONFLICT, "SLOT_ALREADY_RESERVED", "The selected slot is already reserved");
			case HOLD_NOT_FOUND -> problem(HttpStatus.NOT_FOUND, "HOLD_NOT_FOUND", "The temporary hold was not found");
			case HOLD_EXPIRED -> holdExpired();
			case HOLD_OWNER_MISMATCH -> problem(HttpStatus.BAD_GATEWAY, "HOLD_OWNER_MISMATCH",
					"The dependency rejected the technical ownership of the hold");
			case EXTERNAL_PATIENT_MISMATCH -> problem(HttpStatus.BAD_GATEWAY, "EXTERNAL_PATIENT_MISMATCH",
					"The dependency rejected the patient identity associated with the hold");
			case RESERVATION_PROCESS_ALREADY_CONFIRMED -> problem(HttpStatus.CONFLICT, "RESERVATION_PROCESS_ALREADY_CONFIRMED",
					"The dependency reports an earlier acceptance that could not be verified locally. Do not automatically repeat the operation");
			case TIMEOUT -> unknownOutcome(HttpStatus.GATEWAY_TIMEOUT);
			case UNAVAILABLE -> unknownOutcome(HttpStatus.SERVICE_UNAVAILABLE);
			case INVALID_RESPONSE -> unknownOutcome(HttpStatus.BAD_GATEWAY);
			case UNAUTHORIZED, FORBIDDEN, REQUEST_REJECTED -> dependencyError();
			case LOCAL_STATE_FAILURE -> problem(HttpStatus.INTERNAL_SERVER_ERROR, "CONFIRMATION_PERSISTENCE_FAILED",
					"Remote acceptance may exist but could not be verified locally. Do not automatically repeat the operation");
		};
	}

	@ExceptionHandler(ProfessionalLookupException.class)
	public ResponseEntity<ProblemDetail> professionalLookup(ProfessionalLookupException failure) {
		LOG.warn("Reservation confirmation professional lookup failed ({})", failure.reason());
		return switch (failure.reason()) {
			case NOT_FOUND -> professionalNotFound();
			case DISABLED -> problem(HttpStatus.BAD_REQUEST, "PROFESSIONAL_DISABLED", "Professional is disabled");
			case CATALOG_TIMEOUT -> problem(HttpStatus.GATEWAY_TIMEOUT, "RESERVATION_DEPENDENCY_TIMEOUT",
					"Current professional information could not be verified in time");
			case CATALOG_UNAVAILABLE -> problem(HttpStatus.SERVICE_UNAVAILABLE, "RESERVATION_DEPENDENCY_UNAVAILABLE",
					"Current professional information is temporarily unavailable");
			case CATALOG_UNAUTHORIZED, CATALOG_FORBIDDEN, INVALID_CATALOG_RESPONSE -> dependencyError();
		};
	}

	@ExceptionHandler(InvalidReservationSlotException.class)
	public ResponseEntity<ProblemDetail> invalidSlot() {
		return problem(HttpStatus.BAD_REQUEST, "INVALID_SLOT", "The selected appointment slot is invalid");
	}

	@ExceptionHandler(InvalidAvailabilityScheduleException.class)
	public ResponseEntity<ProblemDetail> invalidSchedule() {
		LOG.warn("Reservation confirmation current schedule is invalid");
		return dependencyError();
	}

	@ExceptionHandler(RuntimeException.class)
	public ResponseEntity<ProblemDetail> unexpected(RuntimeException failure) {
		// Never log exception messages, rejected patient values or dependency/SQL causes.
		LOG.error("Reservation confirmation failed ({})", failure.getClass().getSimpleName());
		return problem(HttpStatus.INTERNAL_SERVER_ERROR, "RESERVATION_CONFIRMATION_FAILED",
				"Confirmation could not be completed; a remote effect may exist. Do not automatically repeat the operation");
	}

	private static ResponseEntity<ProblemDetail> professionalNotFound() {
		return problem(HttpStatus.NOT_FOUND, "PROFESSIONAL_NOT_FOUND", "Professional not found");
	}

	private static ResponseEntity<ProblemDetail> holdExpired() {
		return problem(HttpStatus.NOT_FOUND, "HOLD_EXPIRED", "The temporary hold has expired");
	}

	private static ResponseEntity<ProblemDetail> dependencyError() {
		return problem(HttpStatus.BAD_GATEWAY, "RESERVATION_DEPENDENCY_ERROR",
				"A required dependency could not complete initial confirmation");
	}

	private static ResponseEntity<ProblemDetail> unknownOutcome(HttpStatus status) {
		return problem(status, "CONFIRMATION_OUTCOME_UNKNOWN",
				"Remote acceptance may exist; the confirmation outcome is unknown. Do not automatically repeat the operation");
	}

	private static ResponseEntity<ProblemDetail> problem(HttpStatus status, String code, String detail) {
		var body = ProblemDetail.forStatusAndDetail(status, detail);
		body.setProperty("code", code);
		return ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON)
				.cacheControl(CacheControl.noStore()).body(body);
	}
}
