package ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.kafka.producer;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import tools.jackson.databind.json.JsonMapper;

import ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.config.CatedraIntegrationProperties;
import ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.kafka.config.CatedraKafkaConfiguration;
import ar.edu.um.prog2.appointmentreservation.reservation.application.model.PhoneSubmissionIntent;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = CatedraPhoneSubmissionPublisherKafkaIT.TestConfiguration.class)
@EmbeddedKafka(partitions = 1, topics = "test.a83.phone", brokerProperties = {"offsets.topic.num.partitions=1"})
@DirtiesContext
class CatedraPhoneSubmissionPublisherKafkaIT {
	@Autowired
	private EmbeddedKafkaBroker broker;

	@Test
	void publishesTheStoredEnvelopeToTheConfiguredTopicAndReceivesBrokerAcknowledgment() throws Exception {
		var properties = new CatedraIntegrationProperties("test-group", "test-only-rest-jwt",
				new CatedraIntegrationProperties.RestProperties("https://example.test", Duration.ofSeconds(5), Duration.ofSeconds(30)),
				new CatedraIntegrationProperties.KafkaProperties(broker.getBrokersAsString(), "test-group-consumer", "test.actions", "test.a83.phone"));
		var config = new CatedraKafkaConfiguration();
		var factory = config.catedraKafkaProducerFactory(properties);
		var template = config.catedraKafkaTemplate(factory);
		Map<String, Object> consumerProperties = KafkaTestUtils.consumerProps("a83-observer", "false", broker);
		try (var consumer = new DefaultKafkaConsumerFactory<>(consumerProperties, new StringDeserializer(), new StringDeserializer()).createConsumer()) {
			broker.consumeFromAnEmbeddedTopic(consumer, "test.a83.phone");
			var publisher = config.phoneSubmissionPublisher(template, properties);
			publisher.publish(CatedraPhoneSubmissionPublisherTest.intent("test-group", PhoneSubmissionIntent.State.PENDING))
					.toCompletableFuture().get(10, TimeUnit.SECONDS);
			var record = KafkaTestUtils.getSingleRecord(consumer, "test.a83.phone", Duration.ofSeconds(10));
			assertThat(record.key()).isEqualTo("process-1");
			var json = JsonMapper.builder().build().readTree(record.value());
			assertThat(json.get("eventId").asText()).isEqualTo("submission-1");
			assertThat(json.get("requestEventId").asText()).isEqualTo("original-request");
			assertThat(json.at("/information/phoneNumber").asText()).isEqualTo("00000001234");
			assertThat(record.value()).doesNotContain(properties.technicalJwt());
		} finally {
			template.destroy();
			factory.reset();
		}
	}

	@Configuration(proxyBeanMethods = false)
	static class TestConfiguration { }
}
