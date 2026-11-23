package ar.edu.um.prog2.appointmentreservation.reservation.infrastructure.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import ar.edu.um.prog2.appointmentreservation.reservation.application.usecase.CancelOwnedReservationUseCase;
import ar.edu.um.prog2.appointmentreservation.reservation.domain.model.ReservationProcessStatus;
import ar.edu.um.prog2.appointmentreservation.reservation.infrastructure.web.dto.CancelReservationRequest;

@RestController
@RequestMapping("/api/reservation-processes")
public class ReservationProcessCancellationController {
    private final CancelOwnedReservationUseCase cancellation;
    public ReservationProcessCancellationController(CancelOwnedReservationUseCase cancellation) {
        this.cancellation = cancellation;
    }
    @PostMapping(value = "/{id}/cancel", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<CancellationResponse> cancel(@PathVariable("id") @Positive long id,
            @Valid @RequestBody(required = false) CancelReservationRequest request) {
        var result = cancellation.cancel(id, request == null ? null : request.reason());
        if (result.status() != ReservationProcessStatus.CANCELLED) {
            throw new IllegalStateException("Verified cancellation is required");
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new CancellationResponse(result.id(), result.status()));
    }
    public record CancellationResponse(long id, ReservationProcessStatus status) { }
}
