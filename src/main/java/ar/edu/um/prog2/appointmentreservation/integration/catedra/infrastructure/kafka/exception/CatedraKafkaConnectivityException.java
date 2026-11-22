package ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.kafka.exception;

public class CatedraKafkaConnectivityException extends RuntimeException {

	public enum Reason {
		ACCESS,
		UNAVAILABLE,
		INFRASTRUCTURE
	}

	private final Reason reason;

	public CatedraKafkaConnectivityException(Reason reason) {
		super("Catedra Kafka connectivity verification failed: " + reason);
		this.reason = reason;
	}

	public Reason reason() {
		return reason;
	}
}
