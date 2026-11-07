package ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.kafka.config;

import java.util.Map;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;

import ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.config.CatedraIntegrationProperties;

@Configuration(proxyBeanMethods = false)
public class CatedraKafkaConfiguration {

	@Bean
	public ConsumerFactory<String, String> catedraKafkaConsumerFactory(CatedraIntegrationProperties properties) {
		var kafka = properties.kafka();
		Map<String, Object> config = Map.of(
				ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.bootstrapServers(),
				ConsumerConfig.GROUP_ID_CONFIG, kafka.consumerGroupId(),
				ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
				ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
				ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false,
				ConsumerConfig.ALLOW_AUTO_CREATE_TOPICS_CONFIG, false);
		return new DefaultKafkaConsumerFactory<>(config);
	}

	@Bean
	public ProducerFactory<String, String> catedraKafkaProducerFactory(CatedraIntegrationProperties properties) {
		Map<String, Object> config = Map.of(
				ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, properties.kafka().bootstrapServers(),
				ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
				ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
				ProducerConfig.ACKS_CONFIG, "all",
				ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
		return new DefaultKafkaProducerFactory<>(config);
	}

	@Bean
	public KafkaTemplate<String, String> catedraKafkaTemplate(ProducerFactory<String, String> producerFactory) {
		return new KafkaTemplate<>(producerFactory);
	}
}
