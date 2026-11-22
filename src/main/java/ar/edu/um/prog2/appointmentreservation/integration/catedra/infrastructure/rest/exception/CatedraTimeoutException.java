package ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.rest.exception;

public final class CatedraTimeoutException extends CatedraRestException {

	public CatedraTimeoutException() {
		super("Catedra request timed out; the remote outcome may be unknown");
	}
}
