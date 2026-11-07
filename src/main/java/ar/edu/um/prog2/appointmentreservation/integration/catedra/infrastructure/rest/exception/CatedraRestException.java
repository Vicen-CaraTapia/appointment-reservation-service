package ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.rest.exception;

public abstract class CatedraRestException extends RuntimeException {

	protected CatedraRestException(String message) {
		super(message);
	}
}
