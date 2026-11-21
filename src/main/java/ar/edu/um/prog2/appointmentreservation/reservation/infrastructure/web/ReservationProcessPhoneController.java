package ar.edu.um.prog2.appointmentreservation.reservation.infrastructure.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import ar.edu.um.prog2.appointmentreservation.reservation.application.usecase.GetReservationProcessProgressUseCase;
import ar.edu.um.prog2.appointmentreservation.reservation.application.usecase.SubmitPhoneInformationUseCase;
import ar.edu.um.prog2.appointmentreservation.reservation.infrastructure.web.dto.SubmitPhoneRequest;
import ar.edu.um.prog2.appointmentreservation.reservation.infrastructure.web.dto.ReservationProcessProgressResponse;

@RestController
@RequestMapping("/api/reservation-processes")
public class ReservationProcessPhoneController {
	private final SubmitPhoneInformationUseCase submit;
	private final GetReservationProcessProgressUseCase progress;
	public ReservationProcessPhoneController(SubmitPhoneInformationUseCase submit, GetReservationProcessProgressUseCase progress) {
		this.submit = submit;
		this.progress = progress;
	}
	@PostMapping(value = "/{id}/phone", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<ReservationProcessProgressResponse> submit(@PathVariable("id") @Positive long id,
			@Valid @RequestBody SubmitPhoneRequest request) {
		submit.submit(id, request.phoneNumber());
		// Observe durable progress again: an authoritative event may already have advanced it.
		var observed = progress.get(id);
		return ResponseEntity.status(HttpStatus.ACCEPTED).cacheControl(CacheControl.noStore())
				.body(new ReservationProcessProgressResponse(observed.id(), observed.status(), observed.expiresAt(),
						observed.requiredAction(), observed.reason()));
	}
}
