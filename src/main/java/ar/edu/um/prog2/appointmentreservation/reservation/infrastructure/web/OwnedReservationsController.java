package ar.edu.um.prog2.appointmentreservation.reservation.infrastructure.web;

import java.time.LocalDate;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import ar.edu.um.prog2.appointmentreservation.reservation.application.model.OwnedReservationQuery;
import ar.edu.um.prog2.appointmentreservation.reservation.application.model.TechnicalReservation;
import ar.edu.um.prog2.appointmentreservation.reservation.application.usecase.ListOwnedReservationsUseCase;
import ar.edu.um.prog2.appointmentreservation.reservation.infrastructure.web.dto.OwnedReservationsResponse;

@RestController
@RequestMapping("/api/reservations")
public class OwnedReservationsController {
	private final ListOwnedReservationsUseCase reservations;

	public OwnedReservationsController(ListOwnedReservationsUseCase reservations) {
		this.reservations = reservations;
	}

	@GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<OwnedReservationsResponse> list(
			@RequestParam(required = false) TechnicalReservation.Status reservationStatus,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
			@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "20") int size) {
		var result = reservations.list(new OwnedReservationQuery(reservationStatus, from, to, page, size));
		return ResponseEntity.ok().cacheControl(CacheControl.noStore())
				.body(OwnedReservationsResponse.from(result));
	}
}
