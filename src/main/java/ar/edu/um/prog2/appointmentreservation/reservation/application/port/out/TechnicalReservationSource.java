package ar.edu.um.prog2.appointmentreservation.reservation.application.port.out;

import ar.edu.um.prog2.appointmentreservation.reservation.application.model.ReservationListQuery;
import ar.edu.um.prog2.appointmentreservation.reservation.application.model.TechnicalReservationPage;

/** May return different final users. Callers must apply their own trusted ownership association. */
public interface TechnicalReservationSource {
	TechnicalReservationPage list(ReservationListQuery query);
}
