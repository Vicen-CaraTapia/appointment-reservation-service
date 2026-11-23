package ar.edu.um.prog2.appointmentreservation.reservation.infrastructure.web;

import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import ar.edu.um.prog2.appointmentreservation.reservation.application.exception.*;

@RestControllerAdvice(assignableTypes = ReservationProcessCancellationController.class)
public class ReservationProcessCancellationExceptionHandler {
    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,
            HandlerMethodValidationException.class, MethodArgumentNotValidException.class, IllegalArgumentException.class})
    public ResponseEntity<ProblemDetail> invalid() {
        return problem(HttpStatus.BAD_REQUEST, "INVALID_REQUEST");
    }
    @ExceptionHandler(ReservationAuthenticationRequiredException.class)
    public ResponseEntity<ProblemDetail> unauthorized() {
        return ResponseEntity.status(401).contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .cacheControl(CacheControl.noStore()).header(HttpHeaders.WWW_AUTHENTICATE, "Bearer")
                .body(body(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED"));
    }
    @ExceptionHandler(ReservationProcessStateException.class)
    public ResponseEntity<ProblemDetail> local(ReservationProcessStateException failure) {
        return switch (failure.reason()) {
            case NOT_FOUND -> problem(HttpStatus.NOT_FOUND, "RESERVATION_PROCESS_NOT_FOUND");
            case CONFLICT, EXPIRED -> problem(HttpStatus.CONFLICT, "RESERVATION_NOT_CANCELLABLE");
            case STORAGE_FAILURE -> unknown();
        };
    }
    @ExceptionHandler(ReservationCancellationException.class)
    public ResponseEntity<ProblemDetail> remote(ReservationCancellationException failure) {
        return switch (failure.reason()) {
            case RESERVATION_NOT_CANCELLABLE, CONFLICT -> problem(HttpStatus.CONFLICT, "RESERVATION_NOT_CANCELLABLE");
            case RESERVATION_NOT_FOUND, NOT_FOUND -> problem(HttpStatus.BAD_GATEWAY, "CATEDRA_RESERVATION_NOT_FOUND");
            case RESERVATION_OWNER_MISMATCH -> problem(HttpStatus.BAD_GATEWAY, "CATEDRA_OWNER_MISMATCH");
            case UNAUTHORIZED -> problem(HttpStatus.BAD_GATEWAY, "CATEDRA_UNAUTHORIZED");
            case FORBIDDEN, INTEGRATION_NOT_FOUND -> problem(HttpStatus.BAD_GATEWAY, "CATEDRA_FORBIDDEN");
            case REQUEST_REJECTED -> problem(HttpStatus.BAD_GATEWAY, "CATEDRA_REQUEST_REJECTED");
            case TIMEOUT, UNAVAILABLE, REMOTE_FAILURE, INVALID_RESPONSE, OUTCOME_UNKNOWN -> unknown();
        };
    }
    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<ProblemDetail> unknown() {
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "CANCELLATION_OUTCOME_UNKNOWN");
    }
    private static ResponseEntity<ProblemDetail> problem(HttpStatus status, String code) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .cacheControl(CacheControl.noStore()).body(body(status, code));
    }
    private static ProblemDetail body(HttpStatus status, String code) {
        var body = ProblemDetail.forStatusAndDetail(status,
                "Cancellation could not be completed; consult reservation process progress");
        body.setProperty("code", code);
        return body;
    }
}
