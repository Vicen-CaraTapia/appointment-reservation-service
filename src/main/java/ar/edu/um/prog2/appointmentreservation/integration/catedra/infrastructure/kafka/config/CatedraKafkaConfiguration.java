package ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.kafka.config;

import java.util.Map;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.support.ProducerListener;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.CommonContainerStoppingErrorHandler;
import org.springframework.kafka.listener.ContainerProperties.AckMode;

import ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.config.CatedraIntegrationProperties;
import ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.kafka.producer.CatedraPhoneSubmissionPublisher;
import ar.edu.um.prog2.appointmentreservation.reservation.application.port.out.PhoneSubmissionPublisher;
import ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.kafka.event.CatedraAppointmentActionDecoder;

@Configuration(proxyBeanMethods = false)
@EnableKafka
public class CatedraKafkaConfiguration {

	@Bean
	public PhoneSubmissionPublisher phoneSubmissionPublisher(KafkaTemplate<String, String> kafka,
			CatedraIntegrationProperties properties) {
		return new CatedraPhoneSubmissionPublisher(kafka, properties.kafka().appointmentPhoneTopic(), properties.groupId());
	}

	@Bean
	public CatedraAppointmentActionDecoder catedraAppointmentActionDecoder(CatedraIntegrationProperties properties) {
		return new CatedraAppointmentActionDecoder(properties.groupId());
	}

	@Bean
	public ConsumerFactory<String, String> catedraKafkaConsumerFactory(CatedraIntegrationProperties properties) {
		var kafka = properties.kafka();
		Map<String, Object> config = Map.of(
				ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.bootstrapServers(),
				ConsumerConfig.GROUP_ID_CONFIG, kafka.consumerGroupId(),
				ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
				ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
				ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false,
				ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
				ConsumerConfig.ALLOW_AUTO_CREATE_TOPICS_CONFIG, false);
		return new DefaultKafkaConsumerFactory<>(config);
	}

	@Bean
	public ConcurrentKafkaListenerContainerFactory<String, String> catedraKafkaListenerContainerFactory(
			ConsumerFactory<String, String> consumerFactory, CommonContainerStoppingErrorHandler errorHandler,
			@Value("${spring.kafka.listener.auto-startup:true}") boolean autoStartup) {
		var factory = new ConcurrentKafkaListenerContainerFactory<String, String>();
		factory.setConsumerFactory(consumerFactory);
		factory.setAutoStartup(autoStartup);
		factory.setCommonErrorHandler(errorHandler);
		factory.getContainerProperties().setAckMode(AckMode.RECORD);
		return factory;
	}

	@Bean
	public CommonContainerStoppingErrorHandler catedraKafkaErrorHandler() {
		return new CommonContainerStoppingErrorHandler();
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
		var template = new KafkaTemplate<String, String>(producerFactory);
		// The default error listener can log the record value, which contains personal data.
		template.setProducerListener(new ProducerListener<>() {
			@Override
			public void onError(ProducerRecord<String, String> record, RecordMetadata metadata, Exception failure) {
				LoggerFactory.getLogger(CatedraKafkaConfiguration.class).warn("Catedra Kafka publication failed");
			}
		});
		return template;
	}
}
