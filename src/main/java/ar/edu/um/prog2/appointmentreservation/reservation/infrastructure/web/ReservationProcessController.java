package ar.edu.um.prog2.appointmentreservation.reservation.infrastructure.web;

import jakarta.validation.Valid;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import ar.edu.um.prog2.appointmentreservation.reservation.application.model.StartReservationCommand;
import ar.edu.um.prog2.appointmentreservation.reservation.application.usecase.StartReservationProcessUseCase;
import ar.edu.um.prog2.appointmentreservation.reservation.domain.model.ReservationProcessStatus;
import ar.edu.um.prog2.appointmentreservation.reservation.infrastructure.web.dto.StartReservationRequest;
import ar.edu.um.prog2.appointmentreservation.reservation.infrastructure.web.dto.StartReservationResponse;

@RestController
@RequestMapping("/api/reservation-processes")
public class ReservationProcessController {

	private final StartReservationProcessUseCase startReservation;

	public ReservationProcessController(StartReservationProcessUseCase startReservation) {
		this.startReservation = startReservation;
	}

	@PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<StartReservationResponse> start(@Valid @RequestBody StartReservationRequest request) {
		var selection = new StartReservationCommand(request.professionalId(), request.date(), request.startTime());
		var process = startReservation.start(selection);
		return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
				.body(new StartReservationResponse(process.id(), ReservationProcessStatus.HELD, process.hold().expiresAt()));
	}
}
