package ar.edu.um.prog2.appointmentreservation.reservation.infrastructure.web.dto;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonFormat;

import ar.edu.um.prog2.appointmentreservation.reservation.domain.model.ReservationProcessStatus;

/** Public result: local process identity only, without owner, external identifiers or credentials. */
public record StartReservationResponse(long id, ReservationProcessStatus status,
		@JsonFormat(shape = JsonFormat.Shape.STRING) Instant expiresAt) {
}
