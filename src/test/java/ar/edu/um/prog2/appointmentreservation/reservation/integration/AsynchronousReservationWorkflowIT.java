package ar.edu.um.prog2.appointmentreservation.reservation.integration;

import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
import com.sun.net.httpserver.HttpServer;
import org.apache.kafka.clients.admin.*;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.*;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.ApplicationListener;
import org.springframework.kafka.event.ConsumerStoppedEvent;
import org.springframework.context.annotation.*;
import org.springframework.core.env.MapPropertySource;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.*;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.kafka.test.*;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.*;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import ar.edu.um.prog2.appointmentreservation.AppointmentReservationApplication;
import ar.edu.um.prog2.appointmentreservation.professional.application.model.CurrentProfessional;
import ar.edu.um.prog2.appointmentreservation.professional.application.port.out.CurrentProfessionalSource;
import ar.edu.um.prog2.appointmentreservation.reservation.application.model.*;
import ar.edu.um.prog2.appointmentreservation.reservation.application.port.out.ReservationEventStore;
import ar.edu.um.prog2.appointmentreservation.reservation.application.usecase.SubmitPhoneInformationUseCase;
import ar.edu.um.prog2.appointmentreservation.reservation.infrastructure.persistence.repository.ReservationProcessJpaRepository;
import ar.edu.um.prog2.appointmentreservation.support.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real HTTP, REST adapter, Kafka producer/listener, broker and PostgreSQL. Only cátedra REST and Catalog are simulated. */
@ExtendWith({SpringExtension.class, OutputCaptureExtension.class})
@ContextConfiguration(classes = AsynchronousReservationWorkflowIT.BrokerContext.class)
@EmbeddedKafka(partitions = 1, brokerProperties = {"offsets.topic.num.partitions=1"})
@DirtiesContext
class AsynchronousReservationWorkflowIT {
	private static final String PASSWORD = "test-only-asynchronous-password";
	private static final String TECHNICAL = "test-only-catedra-backend-jwt";
	private static final String PHONE = "00000001234", CORRECTION = "00000005678";
	private static final String PROCESS = "a85-process", HOLD = "a85-hold", REQUEST = "a85-request";
	private static final LocalDate DATE = LocalDate.of(2030, 1, 3);
	@Autowired private EmbeddedKafkaBroker broker;
	private ConfigurableApplicationContext application;
	private HttpServer catedra;
	private HttpClient client;
	private Consumer<String, String> observer;
	private Admin admin;
	private KafkaTemplate<String, String> kafka;
	private JsonMapper json;
	private String schema, url, username, password, actions, phoneTopic, group, baseUrl, token;
	private final List<String> tokens = new ArrayList<>();
	private final AtomicInteger confirmationPosts = new AtomicInteger();
	private final CountDownLatch remoteEntered = new CountDownLatch(1), remoteRelease = new CountDownLatch(1);
	private final Faults faults = new Faults();
	private volatile boolean blockConfirmation;
	private volatile String remoteAuthorization;
	private Instant expires;
	private long id;

	@BeforeEach
	void startIsolatedPostgresHttpApplicationAndRealBroker() throws Exception {
		url = required("APPOINTMENT_TEST_DB_URL"); username = required("APPOINTMENT_TEST_DB_USERNAME"); password = required("APPOINTMENT_TEST_DB_PASSWORD");
		String suffix = UUID.randomUUID().toString().replace("-", "");
		schema = "appointment_a85_it_" + suffix;
		actions = "test.a85.actions." + suffix; phoneTopic = "test.a85.phone." + suffix; group = "test-a85-" + suffix;
		try (var connection = connection(); var statement = connection.createStatement()) {
			try (var result = statement.executeQuery("SELECT current_database()")) { result.next(); assertThat(result.getString(1)).startsWith("appointment_"); }
			statement.execute("CREATE SCHEMA " + schema);
		}
		broker.addTopics(actions, phoneTopic);
		admin = Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, broker.getBrokersAsString()));
		observer = new DefaultKafkaConsumerFactory<>(KafkaTestUtils.consumerProps("observer-" + suffix, "false", broker),
				new StringDeserializer(), new StringDeserializer()).createConsumer();
		broker.consumeFromAnEmbeddedTopic(observer, phoneTopic);
		expires = Instant.now().plusSeconds(300).truncatedTo(ChronoUnit.MICROS);
		catedra = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		catedra.createContext("/", exchange -> {
			try (exchange) {
				remoteAuthorization = exchange.getRequestHeaders().getFirst("Authorization");
				exchange.getRequestBody().readAllBytes();
				String body;
				if (exchange.getRequestURI().getPath().endsWith("/confirm")) {
					confirmationPosts.incrementAndGet(); remoteEntered.countDown();
					if (blockConfirmation) {
						try { if (!remoteRelease.await(15, TimeUnit.SECONDS)) { throw new IllegalStateException("Controlled REST wait expired"); } }
						catch (InterruptedException failure) { Thread.currentThread().interrupt(); return; }
					}
					body = "{\"reservationProcessId\":\"" + PROCESS + "\",\"status\":\"WAITING_FOR_PHONE\",\"expiresAt\":\"" + expires + "\"}";
					exchange.getResponseHeaders().set("Content-Type", "application/json");
					byte[] bytes = body.getBytes(StandardCharsets.UTF_8); exchange.sendResponseHeaders(202, bytes.length); exchange.getResponseBody().write(bytes);
				} else {
					body = "{\"holdId\":\"" + HOLD + "\",\"reservationProcessId\":\"" + PROCESS + "\",\"status\":\"HELD\",\"expiresAt\":\"" + expires + "\"}";
					exchange.getResponseHeaders().set("Content-Type", "application/json");
					byte[] bytes = body.getBytes(StandardCharsets.UTF_8); exchange.sendResponseHeaders(201, bytes.length); exchange.getResponseBody().write(bytes);
				}
			}
		});
		catedra.start(); client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
		startApplication(); token = login("alice");
		var created = post("/api/reservation-processes", token, json.writeValueAsString(Map.of("professionalId", 202, "date", DATE.toString(), "startTime", "18:00")));
		assertThat(created.statusCode()).isEqualTo(201); id = json.readTree(created.body()).get("id").asLong();
	}

	@SuppressWarnings("unchecked")
	private void startApplication() {
		Map<String, Object> settings = new HashMap<>();
		settings.put(FinalUserJwtTestSupport.SECRET_PROPERTY, FinalUserJwtTestSupport.signingSecret());
		settings.put("spring.datasource.url", url); settings.put("spring.datasource.username", username); settings.put("spring.datasource.password", password);
		settings.put("spring.flyway.schemas", schema); settings.put("spring.flyway.default-schema", schema); settings.put("spring.flyway.create-schemas", false);
		settings.put("spring.jpa.properties.hibernate.default_schema", schema); settings.put("spring.jpa.hibernate.ddl-auto", "validate");
		settings.put("server.port", 0); settings.put("catedra.kafka.bootstrap-servers", broker.getBrokersAsString());
		settings.put("catedra.kafka.consumer-group-id", group); settings.put("catedra.kafka.appointment-actions-topic", actions);
		settings.put("catedra.kafka.appointment-phone-topic", phoneTopic); settings.put("spring.kafka.listener.auto-startup", true);
		settings.put("catedra.technical-jwt", TECHNICAL); settings.put("catedra.rest.base-url", "http://127.0.0.1:" + catedra.getAddress().getPort());
		settings.put("catedra.rest.read-timeout", "20s");
		application = new SpringApplicationBuilder(AppointmentReservationApplication.class, ControlledBoundaries.class)
				.initializers(CatedraIntegrationTestSupport::configure, CatalogIntegrationTestSupport::configure)
				.initializers(context -> {
					context.getBeanFactory().registerSingleton("workflowFaults", faults);
					context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("a85Test", settings));
				}).web(WebApplicationType.SERVLET).run();
		baseUrl = "http://127.0.0.1:" + application.getEnvironment().getProperty("local.server.port");
		json = application.getBean(JsonMapper.class); kafka = application.getBean(KafkaTemplate.class);
		ContainerTestUtils.waitForAssignment(container(), 1);
	}

	@AfterEach
	void cleanOnlyDisposableResourcesAndVerifyLogs(CapturedOutput output) throws Exception {
		remoteRelease.countDown();
		try {
			if (client != null) { client.close(); } if (application != null) { application.close(); }
			if (observer != null) { observer.close(); } if (admin != null) { admin.close(); } if (catedra != null) { catedra.stop(0); }
		} finally {
			if (schema != null) { try (var connection = connection(); var statement = connection.createStatement()) { statement.execute("DROP SCHEMA " + schema + " CASCADE"); } }
		}
		assertThat(output.getAll()).doesNotContain(PASSWORD, TECHNICAL, PHONE, CORRECTION, "PrivatePatient", "PrivateSurname", "private-after-commit", FinalUserJwtTestSupport.signingSecret());
		for (String issued : tokens) { assertThat(output.getAll()).doesNotContain(issued); }
	}

	@Test
	void realHttpRestPostgresAndKafkaCarryEarlyRequestCorrectionDuplicatesAndLateResults() throws Exception {
		blockConfirmation = true;
		var confirmation = CompletableFuture.supplyAsync(() -> { try { return confirm(); } catch (Exception failure) { throw new CompletionException(failure); } });
		assertThat(remoteEntered.await(5, TimeUnit.SECONDS)).isTrue();
		emit(request()); await(() -> receipt(REQUEST).equals("APPLIED")); remoteRelease.countDown();
		assertThat(confirmation.get(15, TimeUnit.SECONDS).statusCode()).isEqualTo(202);
		awaitAction("SUBMIT_PHONE");
		String bob = login("bob");
		assertThat(get(bob).statusCode()).isEqualTo(404);
		assertThat(post(phonePath(), bob, body(PHONE)).statusCode()).isEqualTo(404);
		assertThat(post(phonePath(), token, "{\"phoneNumber\":\"" + PHONE + "\",\"groupId\":\"other\"}").statusCode()).isEqualTo(400);
		assertThat(observer.poll(Duration.ofMillis(300))).isEmpty();
		assertThat(post(phonePath(), token, body(PHONE)).statusCode()).isEqualTo(202);
		var first = KafkaTestUtils.getSingleRecord(observer, phoneTopic, Duration.ofSeconds(10));
		var envelope = json.readTree(first.value());
		assertThat(first.key()).isEqualTo(PROCESS); assertThat(envelope.get("requestEventId").asText()).isEqualTo(REQUEST);
		assertThat(envelope.get("groupId").asText()).isEqualTo("test-project");
		assertThat(first.value()).doesNotContain(TECHNICAL, token, bob);
		assertThat(post(phonePath(), token, body(PHONE)).statusCode()).isEqualTo(202);
		assertThat(observer.poll(Duration.ofMillis(300))).isEmpty();
		emit(outcome("AdditionalInformationRejected", "rejected")); awaitAction("CORRECT_PHONE");
		assertThat(post(phonePath(), token, body(CORRECTION)).statusCode()).isEqualTo(202);
		var corrected = json.readTree(KafkaTestUtils.getSingleRecord(observer, phoneTopic, Duration.ofSeconds(10)).value());
		assertThat(corrected.get("eventId").asText()).isNotEqualTo(envelope.get("eventId").asText());
		assertThat(corrected.get("requestEventId").asText()).isEqualTo(REQUEST);
		var confirmed = outcome("AppointmentConfirmed", "confirmed");
		emit(confirmed); awaitStatus("CONFIRMED"); long version = version();
		long duplicateOffset = emit(confirmed); await(() -> committedOffset() > duplicateOffset);
		emit(outcome("AppointmentProcessExpired", "late-expiry")); await(() -> receipt("late-expiry").equals("CONFLICT"));
		assertThat(version()).isEqualTo(version); assertThat(status()).isEqualTo("CONFIRMED");
		assertThat(remoteAuthorization).isEqualTo("Bearer " + TECHNICAL);
		assertThat(confirmationPosts).hasValue(1);
	}

	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	void restartAfterFailureBeforeOrAfterCommitRedeliversWithoutDuplicatingEffects(boolean afterCommit) throws Exception {
		assertThat(confirm().statusCode()).isEqualTo(202);
		if (afterCommit) { faults.stopAfterCommit.set(REQUEST); }
		else {
			execute("CREATE FUNCTION " + schema + ".reject_apply() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN "
					+ "IF NEW.handling_status='APPLIED' THEN RAISE EXCEPTION 'controlled pre-commit fault'; END IF; RETURN NEW; END $$");
			execute("CREATE CONSTRAINT TRIGGER reject_apply AFTER UPDATE ON " + schema + ".reservation_event_inbox DEFERRABLE INITIALLY DEFERRED "
					+ "FOR EACH ROW EXECUTE FUNCTION " + schema + ".reject_apply()");
		}
		long offset = emit(request()); await(() -> !container().isRunning());
		assertThat(faults.consumerStopped.await(10, TimeUnit.SECONDS)).isTrue();
		assertThat(committedOffset()).isLessThanOrEqualTo(offset);
		assertThat(receipt(REQUEST)).isEqualTo(afterCommit ? "APPLIED" : "PENDING");
		assertThat(status()).isEqualTo("WAITING_FOR_PHONE"); long before = version();
		if (!afterCommit) { execute("DROP TRIGGER reject_apply ON " + schema + ".reservation_event_inbox"); }
		container().start();
		await(() -> committedOffset() > offset); awaitAction("SUBMIT_PHONE");
		assertThat(receipt(REQUEST)).isEqualTo("APPLIED");
		assertThat(version()).isEqualTo(afterCommit ? before : before + 1);
		assertThat(observer.poll(Duration.ofMillis(300))).isEmpty();
	}

	@Test
	void brokerAcceptedPhoneSurvivesFailedLocalAcknowledgmentCommitAndApplicationRestart() throws Exception {
		assertThat(confirm().statusCode()).isEqualTo(202); emit(request()); awaitAction("SUBMIT_PHONE");
		execute("CREATE FUNCTION " + schema + ".reject_publication() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN "
				+ "IF NEW.publication_state='PUBLISHED' THEN RAISE EXCEPTION 'controlled local acknowledgment fault'; END IF; RETURN NEW; END $$");
		execute("CREATE CONSTRAINT TRIGGER reject_publication AFTER UPDATE ON " + schema + ".reservation_phone_submission DEFERRABLE INITIALLY DEFERRED "
				+ "FOR EACH ROW EXECUTE FUNCTION " + schema + ".reject_publication()");
		var failed = post(phonePath(), token, body(PHONE)); assertThat(failed.statusCode()).isEqualTo(503);
		var original = KafkaTestUtils.getSingleRecord(observer, phoneTopic, Duration.ofSeconds(10));
		assertThat(status()).isEqualTo("PHONE_SUBMISSION_PENDING");
		application.close();
		execute("DROP TRIGGER reject_publication ON " + schema + ".reservation_phone_submission");
		// Simulate elapsed lease time only in this disposable schema, without resetting the durable attempt counter.
		execute("UPDATE " + schema + ".reservation_phone_submission SET publication_lease_until=CURRENT_TIMESTAMP-INTERVAL '1 second'");
		startApplication();
		assertThat(application.getBean(SubmitPhoneInformationUseCase.class).recover(10).published()).isEqualTo(1);
		var repeated = KafkaTestUtils.getSingleRecord(observer, phoneTopic, Duration.ofSeconds(10));
		assertThat(repeated.key()).isEqualTo(original.key()); assertThat(repeated.value()).isEqualTo(original.value());
		assertThat(scalar("SELECT publication_attempts::text FROM " + schema + ".reservation_phone_submission")).isEqualTo("2");
		emit(outcome("AppointmentConfirmed", "recovered-final")); awaitStatus("CONFIRMED");
	}

	@ParameterizedTest
	@ValueSource(strings = {"AppointmentProcessExpired", "AppointmentProcessInvalid"})
	void authoritativeTerminalEventsDisablePhoneWithoutExtendingTheDeadlineOrPublishing(String type) throws Exception {
		assertThat(confirm().statusCode()).isEqualTo(202); emit(request()); awaitAction("SUBMIT_PHONE");
		String originalExpiry = json.readTree(get(token).body()).get("expiresAt").asText();
		emit(outcome(type, "terminal-before-phone"));
		awaitStatus(type.equals("AppointmentProcessExpired") ? "EXPIRED" : "INVALID");
		var observed = json.readTree(get(token).body());
		assertThat(observed.get("requiredAction").asText()).isEqualTo("NONE");
		assertThat(observed.get("expiresAt").asText()).isEqualTo(originalExpiry);
		assertThat(observed.get("reason").asText()).isEqualTo(type.equals("AppointmentProcessExpired") ? "HOLD_EXPIRED" : "REQUEST_EVENT_MISMATCH");
		assertThat(post(phonePath(), token, body(PHONE)).statusCode()).isEqualTo(409);
		assertThat(observer.poll(Duration.ofMillis(300))).isEmpty();
	}

	private JsonNode request() {
		return json.createObjectNode().put("schemaVersion", 1).put("eventId", REQUEST).put("eventType", "AdditionalInformationRequested")
				.put("reservationProcessId", PROCESS).put("holdId", HOLD).put("requiredField", "PHONE_NUMBER").put("message", "Provide phone")
				.put("expiresAt", expires.toString()).put("occurredAt", expires.minusSeconds(300).toString());
	}
	private JsonNode outcome(String type, String eventId) {
		var node = json.createObjectNode().put("schemaVersion", 1).put("eventId", eventId).put("eventType", type).put("groupId", "test-project")
				.put("reservationProcessId", PROCESS).put("holdId", HOLD).put("requestEventId", REQUEST).put("occurredAt", Instant.now().toString());
		switch (type) {
			case "AppointmentConfirmed" -> node.put("status", "CONFIRMED").put("reservationId", 8251).put("professionalId", 202).put("date", DATE.toString()).put("startTime", "18:00:00");
			case "AdditionalInformationRejected" -> node.put("reason", "INVALID_PHONE_NUMBER").put("message", "Correct phone").put("expiresAt", expires.toString());
			case "AppointmentProcessInvalid" -> node.put("reason", "REQUEST_EVENT_MISMATCH");
			default -> node.put("reason", "HOLD_EXPIRED");
		}
		return node;
	}
	private long emit(JsonNode event) throws Exception { return kafka.send(actions, PROCESS, json.writeValueAsString(event)).get(10, TimeUnit.SECONDS).getRecordMetadata().offset(); }
	private long committedOffset() {
		try { var result = admin.listConsumerGroupOffsets(group).partitionsToOffsetAndMetadata().get(5, TimeUnit.SECONDS).get(new TopicPartition(actions, 0)); return result == null ? -1 : result.offset(); }
		catch (Exception failure) { throw new AssertionError("Cannot inspect disposable Kafka offsets", failure); }
	}
	private String receipt(String eventId) { return scalar("SELECT handling_status FROM " + schema + ".reservation_event_inbox WHERE event_id='" + eventId + "'"); }
	private String status() { return scalar("SELECT process_status FROM " + schema + ".reservation_process WHERE id=" + id); }
	private long version() { return Long.parseLong(scalar("SELECT version::text FROM " + schema + ".reservation_process WHERE id=" + id)); }
	private MessageListenerContainer container() { return application.getBean(KafkaListenerEndpointRegistry.class).getListenerContainer("catedraAppointmentActionListener"); }
	private void awaitStatus(String expected) throws Exception { await(() -> status().equals(expected)); assertProgress(get(token)); }
	private void awaitAction(String expected) throws Exception { await(() -> { try { return json.readTree(get(token).body()).get("requiredAction").asText().equals(expected); } catch (Exception failure) { return false; } }); }
	private static void await(BooleanSupplier condition) throws InterruptedException {
		long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
		while (!condition.getAsBoolean() && System.nanoTime() < end) { Thread.sleep(50); }
		assertThat(condition.getAsBoolean()).isTrue();
	}
	private void assertProgress(HttpResponse<String> response) {
		assertThat(response.statusCode()).isEqualTo(200); assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
		assertThat(response.body()).doesNotContain(PASSWORD, TECHNICAL, PHONE, CORRECTION, PROCESS, HOLD);
	}
	private String login(String name) throws Exception {
		assertThat(post("/api/register", null, json.writeValueAsString(Map.of("login", name, "password", PASSWORD, "email", name + "@example.test"))).statusCode()).isEqualTo(201);
		var response = post("/api/authenticate", null, json.writeValueAsString(Map.of("username", name, "password", PASSWORD)));
		assertThat(response.statusCode()).isEqualTo(200); String issued = json.readTree(response.body()).get("id_token").asText(); tokens.add(issued); return issued;
	}
	private HttpResponse<String> confirm() throws Exception { return post("/api/reservation-processes/" + id + "/confirm", token, "{\"patientFirstName\":\"PrivatePatient\",\"patientLastName\":\"PrivateSurname\"}"); }
	private HttpResponse<String> get(String bearer) throws Exception { return client.send(HttpRequest.newBuilder(URI.create(baseUrl + "/api/reservation-processes/" + id)).timeout(Duration.ofSeconds(15)).header("Authorization", "Bearer " + bearer).GET().build(), HttpResponse.BodyHandlers.ofString()); }
	private HttpResponse<String> post(String path, String bearer, String body) throws Exception {
		var request = HttpRequest.newBuilder(URI.create(baseUrl + path)).timeout(Duration.ofSeconds(25)).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body));
		if (bearer != null) { request.header("Authorization", "Bearer " + bearer); } return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
	}
	private String phonePath() { return "/api/reservation-processes/" + id + "/phone"; }
	private static String body(String phone) { return "{\"phoneNumber\":\"" + phone + "\"}"; }
	private String scalar(String sql) {
		try (var connection = connection(); var statement = connection.createStatement(); var result = statement.executeQuery(sql)) { return result.next() ? result.getString(1) : ""; }
		catch (SQLException failure) { throw new AssertionError("Cannot inspect disposable reservation state"); }
	}
	private void execute(String sql) throws SQLException { try (var connection = connection(); var statement = connection.createStatement()) { statement.execute(sql); } }
	private Connection connection() throws SQLException { return DriverManager.getConnection(url, username, password); }
	private static String required(String key) { var value = System.getenv(key); if (value == null || value.isBlank()) { throw new IllegalStateException("Set " + key + " for asynchronous-workflow-integration"); } return value; }
	static final class Faults {
		final AtomicReference<String> stopAfterCommit = new AtomicReference<>();
		final CountDownLatch consumerStopped = new CountDownLatch(1);
	}
	@Configuration(proxyBeanMethods = false) static class BrokerContext { }
	@TestConfiguration(proxyBeanMethods = false)
	static class ControlledBoundaries {
		@Bean ApplicationListener<ConsumerStoppedEvent> stoppedConsumer(Faults faults) {
			return event -> faults.consumerStopped.countDown();
		}
		@Bean @Primary CurrentProfessionalSource controlledCatalog() {
			var source = mock(CurrentProfessionalSource.class);
			when(source.load(202)).thenReturn(new CurrentProfessional(202, "Test", "Professional", true,
					new CurrentProfessional.Category(101, "Test category", true), List.of(new CurrentProfessional.WeeklySchedule(304,
					DATE.getDayOfWeek(), LocalTime.of(18, 0), LocalTime.of(21, 0), 30, true)))); return source;
		}
		@Bean @Primary ReservationEventStore controlledCommitBoundary(@Qualifier("reservationEventStore") ReservationEventStore delegate, Faults faults) {
			return new ReservationEventStore() {
				public ReservationEventReceipt receive(ReservationActionEvent event) { return delegate.receive(event); }
				public ReservationEventReceipt applyPending(String eventId) {
					var result = delegate.applyPending(eventId);
					String armed = faults.stopAfterCommit.get();
					if (eventId.equals(armed) && faults.stopAfterCommit.compareAndSet(armed, null)) { throw new IllegalStateException("private-after-commit"); }
					return result;
				}
				public List<String> findPending(String process, int limit) { return delegate.findPending(process, limit); }
			};
		}
	}
}
