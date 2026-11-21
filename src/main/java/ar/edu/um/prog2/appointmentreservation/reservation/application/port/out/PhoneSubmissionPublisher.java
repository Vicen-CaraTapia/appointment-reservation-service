package ar.edu.um.prog2.appointmentreservation.reservation.application.port.out;

import java.util.concurrent.CompletionStage;

import ar.edu.um.prog2.appointmentreservation.reservation.application.model.PhoneSubmissionIntent;

public interface PhoneSubmissionPublisher {
	/** Completes only after broker acknowledgment; failure may mean an uncertain remote outcome. */
	CompletionStage<Void> publish(PhoneSubmissionIntent intent);
}
