package ar.edu.um.prog2.appointmentreservation.reservation.application.port.out;

import org.jspecify.annotations.Nullable;
import ar.edu.um.prog2.appointmentreservation.reservation.application.model.ReservationCancellation;

/** Technical operation. The caller must establish trusted final-user ownership before invoking it. */
public interface ReservationCanceller {
	ReservationCancellation cancel(String reservationProcessId, @Nullable String reason);
}
