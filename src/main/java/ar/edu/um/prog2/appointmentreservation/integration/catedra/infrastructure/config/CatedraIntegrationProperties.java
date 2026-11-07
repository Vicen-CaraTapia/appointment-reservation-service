package ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@ConfigurationProperties(prefix = "catedra")
@Validated
public record CatedraIntegrationProperties(
		@NotBlank String groupId,
		@NotBlank String technicalJwt,
		@NotNull @Valid RestProperties rest,
		@NotNull @Valid KafkaProperties kafka
) {

	@Override
	public String toString() {
		return "CatedraIntegrationProperties[redacted]";
	}

	public record RestProperties(@NotBlank String baseUrl) {
	}

	public record KafkaProperties(
			@NotBlank String bootstrapServers,
			@NotBlank String consumerGroupId,
			@NotBlank String appointmentActionsTopic,
			@NotBlank String appointmentPhoneTopic
	) {
	}
}
