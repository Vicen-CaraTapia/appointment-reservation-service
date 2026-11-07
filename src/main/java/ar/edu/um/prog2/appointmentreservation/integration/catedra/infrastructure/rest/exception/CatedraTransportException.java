package ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.rest.exception;

public final class CatedraTransportException extends CatedraRestException {

	public CatedraTransportException() {
		super("Catedra connection failed; the remote outcome may be unknown");
	}
}
