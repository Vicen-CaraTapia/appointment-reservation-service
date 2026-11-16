package ar.edu.um.prog2.appointmentreservation.availability.infrastructure.web;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import ar.edu.um.prog2.appointmentreservation.availability.application.usecase.CalculateAppointmentAvailabilityUseCase;
import ar.edu.um.prog2.appointmentreservation.availability.infrastructure.web.dto.AppointmentAvailabilityResponse;

@RestController
@RequestMapping("/api/appointment-availability")
public class AppointmentAvailabilityController {

	private final CalculateAppointmentAvailabilityUseCase availability;

	public AppointmentAvailabilityController(CalculateAppointmentAvailabilityUseCase availability) {
		this.availability = availability;
	}

	@GetMapping
	public ResponseEntity<AppointmentAvailabilityResponse> get(
			@RequestParam("professionalId") long professionalId,
			@RequestParam("date") String requestedDate) {
		var date = LocalDate.parse(requestedDate, DateTimeFormatter.ISO_LOCAL_DATE);
		var slots = availability.calculate(professionalId, date).stream()
				.map(DateTimeFormatter.ISO_LOCAL_TIME::format).toList();
		return ResponseEntity.ok().cacheControl(CacheControl.noStore())
				.body(new AppointmentAvailabilityResponse(professionalId, date, slots));
	}
}
