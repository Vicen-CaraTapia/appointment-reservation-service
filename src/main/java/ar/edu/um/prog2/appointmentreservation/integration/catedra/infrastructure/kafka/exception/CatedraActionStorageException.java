package ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.kafka.exception;

public final class CatedraActionStorageException extends RuntimeException {
	public CatedraActionStorageException() {
		super("Catedra action rejection could not be durably recorded");
	}
}
