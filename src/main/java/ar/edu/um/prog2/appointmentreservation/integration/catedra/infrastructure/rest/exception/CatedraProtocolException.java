package ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.rest.exception;

public final class CatedraProtocolException extends CatedraRestException {

	public CatedraProtocolException() {
		super("Catedra HTTP exchange did not match the expected data contract");
	}
}
