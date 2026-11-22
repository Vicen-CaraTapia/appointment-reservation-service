package ar.edu.um.prog2.appointmentreservation.reservation.application.port.out;

import java.util.List;

import ar.edu.um.prog2.appointmentreservation.reservation.application.model.ReservationActionEvent;
import ar.edu.um.prog2.appointmentreservation.reservation.application.model.ReservationEventReceipt;

/** Durable reservation-event boundary; none of these operations commits Kafka offsets or calls REST. */
public interface ReservationEventStore {
	ReservationEventReceipt receive(ReservationActionEvent event);
	ReservationEventReceipt applyPending(String eventId);
	List<String> findPending(String externalProcessId, int limit);
}
