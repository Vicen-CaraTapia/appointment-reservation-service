package ar.edu.um.prog2.appointmentreservation.reservation.application.port.out;

import ar.edu.um.prog2.appointmentreservation.reservation.application.model.AppointmentHold;
import ar.edu.um.prog2.appointmentreservation.reservation.application.model.ConfirmationPatient;
import ar.edu.um.prog2.appointmentreservation.reservation.application.model.InitialReservationConfirmation;

/** One remote initial-confirmation attempt using only the owned process's trusted identifiers. */
public interface AppointmentHoldConfirmer {

	InitialReservationConfirmation confirm(AppointmentHold hold, String externalPatientId, ConfirmationPatient patient);
}
