package ar.edu.um.prog2.appointmentreservation.reservation.infrastructure.web.dto;

import java.time.Instant;
import com.fasterxml.jackson.annotation.JsonFormat;
import ar.edu.um.prog2.appointmentreservation.reservation.domain.model.ReservationProcessStatus;
import ar.edu.um.prog2.appointmentreservation.reservation.application.model.ReservationProcessProgress.RequiredAction;
import ar.edu.um.prog2.appointmentreservation.reservation.application.model.ReservationProcessProgress.Reason;

/** Public progress only: no owner, technical IDs, phone or patient names. */
public record ReservationProcessProgressResponse(long id, ReservationProcessStatus status,
		@JsonFormat(shape = JsonFormat.Shape.STRING) Instant expiresAt, RequiredAction requiredAction, Reason reason) { }
