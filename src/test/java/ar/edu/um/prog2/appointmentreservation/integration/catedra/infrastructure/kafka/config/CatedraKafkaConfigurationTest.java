package ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.kafka.config;

import java.time.Duration;
import java.util.stream.Stream;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.config.SaslConfigs;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.MessageListenerContainer;

import ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.config.CatedraIntegrationProperties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.spy;

class CatedraKafkaConfigurationTest {

	@ParameterizedTest
	@CsvSource({
			"first.example.test:19092, externally-assigned-group-a",
			"second.example.test:29092, externally-assigned-group-b"
	})
	void usesExternalBrokersAndConsumerGroup(String brokers, String groupId) {
		var properties = properties(brokers, groupId);
		var configuration = new CatedraKafkaConfiguration();
		var consumer = configuration.catedraKafkaConsumerFactory(properties);
		var producer = configuration.catedraKafkaProducerFactory(properties);

		assertThat(consumer.getConfigurationProperties())
				.containsEntry(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, brokers)
				.containsEntry(ConsumerConfig.GROUP_ID_CONFIG, groupId);
		assertThat(producer.getConfigurationProperties())
				.containsEntry(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, brokers);
	}

	@Test
	void configuresStringDeserializersWithoutAutomaticCommitsOrTopicCreation() {
		var consumer = new CatedraKafkaConfiguration().catedraKafkaConsumerFactory(properties());
		assertThat(consumer.getConfigurationProperties())
				.containsEntry(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class)
				.containsEntry(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class)
				.containsEntry(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false)
				.containsEntry(ConsumerConfig.ALLOW_AUTO_CREATE_TOPICS_CONFIG, false)
				.doesNotContainKeys(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, ConsumerConfig.MAX_POLL_RECORDS_CONFIG)
				.doesNotContainKey(SaslConfigs.SASL_JAAS_CONFIG)
				.doesNotContainValue(properties().technicalJwt());
	}

	@Test
	void configuresStringSerializersAllAcknowledgmentsAndProducerIdempotence() {
		var producer = new CatedraKafkaConfiguration().catedraKafkaProducerFactory(properties());
		assertThat(producer.getConfigurationProperties())
				.containsEntry(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class)
				.containsEntry(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class)
				.containsEntry(ProducerConfig.ACKS_CONFIG, "all")
				.containsEntry(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true)
				.doesNotContainKey(SaslConfigs.SASL_JAAS_CONFIG)
				.doesNotContainValue(properties().technicalJwt());
	}

	@Test
	void createsTheTemplateAndFactoriesWithoutCreatingClientsOrRegisteringListeners() {
		new ApplicationContextRunner()
				.withBean(CatedraIntegrationProperties.class, CatedraKafkaConfigurationTest::properties)
				.withUserConfiguration(CatedraKafkaConfiguration.class, TrackClientCreation.class)
				.run(context -> {
					assertThat(context).hasNotFailed();
					assertThat(context).hasSingleBean(ConsumerFactory.class)
							.hasSingleBean(ProducerFactory.class).hasSingleBean(KafkaTemplate.class);
					ConsumerFactory<?, ?> consumer = context.getBean(ConsumerFactory.class);
					ProducerFactory<?, ?> producer = context.getBean(ProducerFactory.class);
					KafkaTemplate<?, ?> template = context.getBean(KafkaTemplate.class);
					assertThat(template.getProducerFactory()).isSameAs(producer);
					assertThat(template.getDefaultTopic()).isNull();
					assertThat(context.getBeansOfType(KafkaListenerEndpointRegistry.class)).isEmpty();
					assertThat(context.getBeansOfType(MessageListenerContainer.class)).isEmpty();
					Stream.of(consumer, producer, template).forEach(bean ->
							assertThat(mockingDetails(bean).getInvocations())
									.extracting(invocation -> invocation.getMethod().getName())
									.noneMatch(name -> name.startsWith("createConsumer")
											|| name.startsWith("createProducer") || name.startsWith("send")
											|| name.equals("receive")));
				});
	}

	private static CatedraIntegrationProperties properties() {
		return properties("kafka.example.test:9092", "test-assigned-group");
	}

	private static CatedraIntegrationProperties properties(String brokers, String consumerGroup) {
		return new CatedraIntegrationProperties("test-project", "test-only-rest-jwt",
				new CatedraIntegrationProperties.RestProperties("https://catedra.example.test",
						Duration.ofSeconds(5), Duration.ofSeconds(30)),
				new CatedraIntegrationProperties.KafkaProperties(brokers, consumerGroup,
						"test-assigned-actions-topic", "test-assigned-phone-topic"));
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class TrackClientCreation {

		@Bean
		static BeanPostProcessor kafkaInfrastructureSpies() {
			return new BeanPostProcessor() {
				@Override
				public Object postProcessAfterInitialization(Object bean, String beanName) {
					return bean instanceof ConsumerFactory<?, ?> || bean instanceof ProducerFactory<?, ?>
							|| bean instanceof KafkaTemplate<?, ?> ? spy(bean) : bean;
				}
			};
		}
	}
}
