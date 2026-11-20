package ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.kafka.exception;

/** Safe failure signal for the listener container; deliberately retains no record or cause. */
public final class CatedraActionProcessingException extends RuntimeException {
	public CatedraActionProcessingException() {
		super("Catedra appointment action could not be processed");
	}
}
