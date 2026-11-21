package ar.edu.um.prog2.appointmentreservation.reservation.infrastructure.web;

import jakarta.validation.constraints.Positive;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import ar.edu.um.prog2.appointmentreservation.reservation.application.usecase.GetReservationProcessProgressUseCase;
import ar.edu.um.prog2.appointmentreservation.reservation.infrastructure.web.dto.ReservationProcessProgressResponse;

@RestController
@RequestMapping("/api/reservation-processes")
public class ReservationProcessProgressController {
	private final GetReservationProcessProgressUseCase progress;
	public ReservationProcessProgressController(GetReservationProcessProgressUseCase progress) { this.progress = progress; }
	@GetMapping(value = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<ReservationProcessProgressResponse> get(@PathVariable("id") @Positive long id) {
		var observation = progress.get(id);
		return ResponseEntity.ok().cacheControl(CacheControl.noStore())
				.body(new ReservationProcessProgressResponse(observation.id(), observation.status(), observation.expiresAt(),
						observation.requiredAction(), observation.reason()));
	}
}
