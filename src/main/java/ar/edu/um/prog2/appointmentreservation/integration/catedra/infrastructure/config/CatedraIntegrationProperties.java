package ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

@Component
@Validated
@ConfigurationProperties(prefix = "catedra")
public class CatedraIntegrationProperties {

	@NotBlank
	private String groupId;

	@NotBlank
	private String technicalJwt;

	@Valid
	@NotNull
	private Rest rest = new Rest();

	@Valid
	@NotNull
	private Kafka kafka = new Kafka();

	public String getGroupId() {
		return groupId;
	}

	public void setGroupId(String groupId) {
		this.groupId = groupId;
	}

	public String getTechnicalJwt() {
		return technicalJwt;
	}

	public void setTechnicalJwt(String technicalJwt) {
		this.technicalJwt = technicalJwt;
	}

	public Rest getRest() {
		return rest;
	}

	public void setRest(Rest rest) {
		this.rest = rest;
	}

	public Kafka getKafka() {
		return kafka;
	}

	public void setKafka(Kafka kafka) {
		this.kafka = kafka;
	}

	@Override
	public String toString() {
		return "CatedraIntegrationProperties[redacted]";
	}

	public static class Rest {

		@NotBlank
		private String baseUrl;

		public String getBaseUrl() {
			return baseUrl;
		}

		public void setBaseUrl(String baseUrl) {
			this.baseUrl = baseUrl;
		}
	}

	public static class Kafka {

		@NotBlank
		private String bootstrapServers;

		@NotBlank
		private String consumerGroupId;

		@NotBlank
		private String appointmentActionsTopic;

		@NotBlank
		private String appointmentPhoneTopic;

		public String getBootstrapServers() {
			return bootstrapServers;
		}

		public void setBootstrapServers(String bootstrapServers) {
			this.bootstrapServers = bootstrapServers;
		}

		public String getConsumerGroupId() {
			return consumerGroupId;
		}

		public void setConsumerGroupId(String consumerGroupId) {
			this.consumerGroupId = consumerGroupId;
		}

		public String getAppointmentActionsTopic() {
			return appointmentActionsTopic;
		}

		public void setAppointmentActionsTopic(String appointmentActionsTopic) {
			this.appointmentActionsTopic = appointmentActionsTopic;
		}

		public String getAppointmentPhoneTopic() {
			return appointmentPhoneTopic;
		}

		public void setAppointmentPhoneTopic(String appointmentPhoneTopic) {
			this.appointmentPhoneTopic = appointmentPhoneTopic;
		}
	}
}
