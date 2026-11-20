package ar.edu.um.prog2.appointmentreservation.reservation.infrastructure.web;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import ar.edu.um.prog2.appointmentreservation.reservation.application.model.ConfirmationPatient;
import ar.edu.um.prog2.appointmentreservation.reservation.application.usecase.ConfirmReservationProcessUseCase;
import ar.edu.um.prog2.appointmentreservation.reservation.infrastructure.web.dto.ConfirmReservationRequest;
import ar.edu.um.prog2.appointmentreservation.reservation.infrastructure.web.dto.ConfirmReservationResponse;

@RestController
@RequestMapping("/api/reservation-processes")
public class ReservationProcessConfirmationController {

	private final ConfirmReservationProcessUseCase confirmReservation;

	public ReservationProcessConfirmationController(ConfirmReservationProcessUseCase confirmReservation) {
		this.confirmReservation = confirmReservation;
	}

	@PostMapping(value = "/{id}/confirm", consumes = MediaType.APPLICATION_JSON_VALUE,
			produces = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<ConfirmReservationResponse> confirm(@PathVariable("id") @Positive long id,
			@Valid @RequestBody ConfirmReservationRequest request) {
		var patient = new ConfirmationPatient(request.patientFirstName(), request.patientLastName());
		var process = confirmReservation.confirm(id, patient);
		return ResponseEntity.status(HttpStatus.ACCEPTED).cacheControl(CacheControl.noStore())
				.body(new ConfirmReservationResponse(process.id(), process.status(), process.hold().expiresAt()));
	}
}
