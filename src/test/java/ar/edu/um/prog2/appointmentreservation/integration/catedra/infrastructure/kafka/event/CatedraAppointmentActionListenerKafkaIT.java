package ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.kafka.event;

import java.time.Duration;
import java.util.Map;
import java.util.function.BooleanSupplier;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.common.TopicPartition;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.ContainerTestUtils;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.context.TestPropertySource;

import ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.config.CatedraIntegrationProperties;
import ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.kafka.config.CatedraKafkaConfiguration;
import ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.kafka.persistence.RejectedCatedraActionStore;
import ar.edu.um.prog2.appointmentreservation.reservation.application.model.ReservationActionEvent;
import ar.edu.um.prog2.appointmentreservation.reservation.application.model.ReservationEventReceipt;
import ar.edu.um.prog2.appointmentreservation.reservation.application.model.ReservationEventReceipt.State;
import ar.edu.um.prog2.appointmentreservation.reservation.application.usecase.ProcessReservationActionUseCase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Uses an in-process broker and controlled application boundaries; never connects to the cátedra. */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = CatedraAppointmentActionListenerKafkaIT.TestConfiguration.class)
@EmbeddedKafka(partitions = 1, topics = CatedraAppointmentActionListenerKafkaIT.TOPIC,
		bootstrapServersProperty = "spring.kafka.bootstrap-servers",
		brokerProperties = {"offsets.topic.num.partitions=1", "transaction.state.log.replication.factor=1",
				"transaction.state.log.min.isr=1"})
@TestPropertySource(properties = {
		"catedra.kafka.appointment-actions-topic=test.a82.appointment.actions",
		"catedra.kafka.consumer-group-id=test-a82-action-listener",
		"spring.kafka.listener.auto-startup=true"})
@DirtiesContext
class CatedraAppointmentActionListenerKafkaIT {
	static final String TOPIC = "test.a82.appointment.actions";
	private static final String GROUP = "test-a82-action-listener";
	private static final String PAYLOAD = """
			{"schemaVersion":1,"eventId":"request-broker-1","eventType":"AdditionalInformationRequested",
			"reservationProcessId":"process-broker-4","holdId":"hold-broker-2","requiredField":"PHONE_NUMBER",
			"message":"Provide a phone number","expiresAt":"2026-11-20T16:30:00Z",
			"occurredAt":"2026-11-20T16:00:00Z"}
			""";

	@Autowired
	private KafkaTemplate<String, String> kafka;
	@Autowired
	private ProcessReservationActionUseCase actions;
	@Autowired
	private RejectedCatedraActionStore rejected;
	@Autowired
	private KafkaListenerEndpointRegistry registry;
	@Autowired
	private EmbeddedKafkaBroker broker;

	@Test
	void consumesOnlyTheConfiguredActionsTopicAndPassesTheDecodedEventToApplicationProcessing() throws Exception {
		var container = registry.getListenerContainer("catedraAppointmentActionListener");
		assertThat(container).isNotNull();
		ContainerTestUtils.waitForAssignment(container, 1);

		var observed = new AtomicReference<ReservationActionEvent>();
		var received = new CountDownLatch(1);
		when(actions.process(any())).thenAnswer(call -> {
			observed.set(call.getArgument(0));
			received.countDown();
			return new ReservationEventReceipt("request-broker-1", State.APPLIED, 42L, false);
		});
		kafka.send(TOPIC, "process-broker-4", PAYLOAD).get(10, TimeUnit.SECONDS);

		assertThat(received.await(10, TimeUnit.SECONDS)).isTrue();
		assertThat(observed.get()).isNotNull();
		assertThat(observed.get().eventId()).isEqualTo("request-broker-1");
		assertThat(observed.get().externalProcessId()).isEqualTo("process-broker-4");
		verify(actions).process(observed.get());
		verifyNoInteractions(rejected);
		assertThat(broker.getTopics()).contains(TOPIC);

		try (var admin = Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, broker.getBrokersAsString()))) {
			await(() -> committedOffset(admin) == 1);
			when(actions.process(any())).thenThrow(new IllegalStateException("simulated storage failure"));
			kafka.send(TOPIC, "process-broker-4", PAYLOAD.replace("request-broker-1", "request-broker-2"))
					.get(10, TimeUnit.SECONDS);
			await(() -> !container.isRunning());
			assertThat(committedOffset(admin)).isEqualTo(1);

			var redelivered = new CountDownLatch(1);
			org.mockito.Mockito.doAnswer(call -> {
				assertThat(((ReservationActionEvent) call.getArgument(0)).eventId()).isEqualTo("request-broker-2");
				redelivered.countDown();
				return new ReservationEventReceipt("request-broker-2", State.APPLIED, 42L, false);
			}).when(actions).process(any());
			container.start();
			assertThat(redelivered.await(15, TimeUnit.SECONDS)).isTrue();
			await(() -> committedOffset(admin) == 2);
			String cancelled = """
					{"schemaVersion":1,"eventId":"cancel-broker","eventType":"AppointmentCancelled",
					"groupId":"test-project","reservationProcessId":"process-broker-4","holdId":"hold-broker-2",
					"requestEventId":"request-broker-1","reservationId":8251,"professionalId":202,
					"date":"2030-01-03","startTime":"18:00","status":"CANCELLED","reason":null,
					"occurredAt":"2030-01-01T12:00:00Z"}
					""";
			org.mockito.Mockito.doThrow(new IllegalStateException("simulated cancellation storage failure"))
					.when(actions).process(any());
			kafka.send(TOPIC,"process-broker-4",cancelled).get(10,TimeUnit.SECONDS);
			await(() -> !container.isRunning());
			assertThat(committedOffset(admin)).isEqualTo(2);
			org.mockito.Mockito.doAnswer(call -> {
				ReservationActionEvent event = call.getArgument(0);
				assertThat(event.signal()).isEqualTo(ar.edu.um.prog2.appointmentreservation.reservation.domain.model.AsynchronousReservationTransitions.Signal.RESERVATION_CANCELLED);
				assertThat(event.reason()).isNull();
				return new ReservationEventReceipt(event.eventId(),State.APPLIED,42L,false);
			}).when(actions).process(any());
			container.start();
			await(() -> committedOffset(admin) == 3);
			// Simulate business commit succeeding, then losing the acknowledgment.
			var committedEffects = new java.util.concurrent.atomic.AtomicInteger();
			var deliveries = new java.util.concurrent.atomic.AtomicInteger();
			org.mockito.Mockito.doAnswer(call -> {
				ReservationActionEvent event = call.getArgument(0);
				if (deliveries.incrementAndGet() == 1) {
					committedEffects.incrementAndGet();
					throw new IllegalStateException("test-only failure after business commit");
				}
				return new ReservationEventReceipt(event.eventId(), State.APPLIED, 42L, true);
			}).when(actions).process(any());
			kafka.send(TOPIC, "process-broker-4", cancelled.replace("cancel-broker", "cancel-after-commit"))
					.get(10, TimeUnit.SECONDS);
			await(() -> !container.isRunning());
			assertThat(committedOffset(admin)).isEqualTo(3);
			assertThat(committedEffects).hasValue(1);
			container.start();
			await(() -> committedOffset(admin) == 4);
			assertThat(deliveries).hasValue(2);
			assertThat(committedEffects).hasValue(1);
		}
	}

	private static long committedOffset(Admin admin) {
		try {
			var offsets = admin.listConsumerGroupOffsets(GROUP).partitionsToOffsetAndMetadata()
					.get(5, TimeUnit.SECONDS);
			var offset = offsets.get(new TopicPartition(TOPIC, 0));
			return offset == null ? -1 : offset.offset();
		} catch (Exception failure) {
			throw new AssertionError("Cannot inspect test consumer offsets", failure);
		}
	}

	private static void await(BooleanSupplier condition) throws InterruptedException {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
		while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
			Thread.sleep(50);
		}
		assertThat(condition.getAsBoolean()).isTrue();
	}

	@Configuration(proxyBeanMethods = false)
	@Import({CatedraKafkaConfiguration.class, CatedraAppointmentActionListener.class})
	static class TestConfiguration {
		@Bean
		CatedraIntegrationProperties catedraProperties(@Value("${spring.kafka.bootstrap-servers}") String brokers) {
			return new CatedraIntegrationProperties("test-project", "test-only-rest-token",
					new CatedraIntegrationProperties.RestProperties("https://catedra.example.test",
							Duration.ofSeconds(5), Duration.ofSeconds(30)),
						new CatedraIntegrationProperties.KafkaProperties(brokers, GROUP, TOPIC, "test.a82.phone"));
		}

		@Bean
		ProcessReservationActionUseCase processReservationActionUseCase() {
			return mock(ProcessReservationActionUseCase.class);
		}

		@Bean
		RejectedCatedraActionStore rejectedCatedraActionStore() {
			return mock(RejectedCatedraActionStore.class);
		}
	}
}
