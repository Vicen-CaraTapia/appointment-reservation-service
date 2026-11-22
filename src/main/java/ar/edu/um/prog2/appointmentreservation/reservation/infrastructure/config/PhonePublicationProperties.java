package ar.edu.um.prog2.appointmentreservation.reservation.infrastructure.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("appointment.phone-publication")
public record PhonePublicationProperties(@DefaultValue("3") int maxAttempts,
		@DefaultValue("3m") Duration lease, @DefaultValue("10s") Duration acknowledgmentTimeout,
		@DefaultValue("5s") Duration retryDelay, @DefaultValue("10s") Duration pollInterval,
		@DefaultValue("10") int batchSize) {
	public PhonePublicationProperties {
		if (maxAttempts < 1 || maxAttempts > 100 || batchSize < 1 || batchSize > 100
				|| !positive(lease) || !positive(acknowledgmentTimeout) || !positive(retryDelay) || !positive(pollInterval)
				|| lease.compareTo(acknowledgmentTimeout.plusSeconds(60)) <= 0) {
			throw new IllegalArgumentException("Invalid bounded phone publication recovery configuration");
		}
	}
	private static boolean positive(Duration value) { return value != null && value.toMillis() > 0; }
}
