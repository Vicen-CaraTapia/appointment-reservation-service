package ar.edu.um.prog2.appointmentreservation.reservation.infrastructure.web;

import java.net.URI;
import java.net.http.*;
import java.sql.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.*;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.*;
import org.springframework.core.env.MapPropertySource;
import org.springframework.kafka.core.KafkaTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import ar.edu.um.prog2.appointmentreservation.AppointmentReservationApplication;
import ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.kafka.event.CatedraAppointmentActionListener;
import ar.edu.um.prog2.appointmentreservation.professional.application.port.out.CurrentProfessionalSource;
import ar.edu.um.prog2.appointmentreservation.reservation.application.model.*;
import ar.edu.um.prog2.appointmentreservation.reservation.application.port.out.ReservationProcessStore;
import ar.edu.um.prog2.appointmentreservation.reservation.application.usecase.SubmitPhoneInformationUseCase;
import ar.edu.um.prog2.appointmentreservation.reservation.infrastructure.persistence.repository.ReservationProcessJpaRepository;
import ar.edu.um.prog2.appointmentreservation.support.*;
import ar.edu.um.prog2.appointmentreservation.user.infrastructure.persistence.repository.UserJpaRepository;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Real HTTP, JWT, producer serialization, listener, application and PostgreSQL; Kafka transport is controlled. */
@ExtendWith(OutputCaptureExtension.class)
class ReservationProcessPhoneHttpIT {
	private static final String PASSWORD = "test-only-phone-workflow-password";
	private static final String PHONE = "00000001234";
	private static final String CORRECTION = "00000005678";
	private static final String PROCESS = "external-workflow-process";
	private static final String HOLD = "external-workflow-hold";
	private final List<String> tokens = new ArrayList<>();
	private final List<Sent> sent = new CopyOnWriteArrayList<>();
	private final AtomicReference<Runnable> duringSend = new AtomicReference<>();
	private volatile boolean failBroker;
	private ConfigurableApplicationContext context;
	private HttpClient client;
	private JsonMapper json;
	private String schema, url, username, password, baseUrl, token;
	private long id, ownerId;
	private Instant expiry;

	@BeforeEach
	void startIsolatedApplicationAndCreateOwnedProcessAwaitingPhoneRequest() throws Exception {
		url = required("APPOINTMENT_TEST_DB_URL"); username = required("APPOINTMENT_TEST_DB_USERNAME"); password = required("APPOINTMENT_TEST_DB_PASSWORD");
		try (var connection = connection(); var statement = connection.createStatement()) {
			try (var result = statement.executeQuery("SELECT current_database()")) {
				result.next(); assertThat(result.getString(1)).startsWith("appointment_");
			}
			schema = "appointment_phone_http_it_" + UUID.randomUUID().toString().replace("-", "");
			statement.execute("CREATE SCHEMA " + schema);
		}
		client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
		startContext();
		token = registerAndLogin("alice");
		ownerId = context.getBean(UserJpaRepository.class).findByLoginIgnoreCase("alice").orElseThrow().getId();
		expiry = Instant.now().plusSeconds(300).truncatedTo(ChronoUnit.MICROS);
		var owner = new AuthenticatedReservationUser(ownerId, "alice");
		var store = context.getBean(ReservationProcessStore.class);
		id = store.createHeld(owner, new StartReservationCommand(202, LocalDate.of(2030, 1, 3), LocalTime.of(18, 0)),
				new AppointmentHold(HOLD, PROCESS, expiry)).id();
		var claim = store.claimInitialConfirmation(id, 0, owner, new ConfirmationPatient("PrivatePatient", "PrivateSurname"), Instant.now());
		store.recordInitialConfirmation(id, claim.version(), owner,
				new InitialReservationConfirmation(PROCESS, "PrivatePatient", "PrivateSurname", expiry));
	}

	@SuppressWarnings("unchecked")
	private void startContext() {
		Map<String, Object> settings = new HashMap<>();
		settings.put(FinalUserJwtTestSupport.SECRET_PROPERTY, FinalUserJwtTestSupport.signingSecret());
		settings.put("spring.datasource.url", url); settings.put("spring.datasource.username", username); settings.put("spring.datasource.password", password);
		settings.put("spring.flyway.schemas", schema); settings.put("spring.flyway.default-schema", schema); settings.put("spring.flyway.create-schemas", false);
		settings.put("spring.jpa.properties.hibernate.default_schema", schema); settings.put("spring.jpa.hibernate.ddl-auto", "validate");
		settings.put("server.port", 0); settings.put("appointment.phone-publication.retry-delay", "1ms");
		context = new SpringApplicationBuilder(AppointmentReservationApplication.class, ControlledTransport.class)
				.initializers(CatedraIntegrationTestSupport::configure, CatalogIntegrationTestSupport::configure)
				.initializers(app -> app.getEnvironment().getPropertySources().addFirst(new MapPropertySource("phoneHttpTest", settings)))
				.web(WebApplicationType.SERVLET).run();
		baseUrl = "http://127.0.0.1:" + context.getEnvironment().getProperty("local.server.port");
		json = context.getBean(JsonMapper.class);
		KafkaTemplate<String, String> kafka = context.getBean(KafkaTemplate.class);
		when(kafka.send(anyString(), anyString(), anyString())).thenAnswer(call -> {
			sent.add(new Sent(call.getArgument(0), call.getArgument(1), call.getArgument(2)));
			var event = duringSend.getAndSet(null); if (event != null) { event.run(); }
			return failBroker ? CompletableFuture.failedFuture(new IllegalStateException("private-broker-diagnostic"))
					: CompletableFuture.completedFuture(null);
		});
	}

	@AfterEach
	void closeAndDropOnlyTheTestSchemaAndCheckSafeLogs(CapturedOutput output) throws Exception {
		try { if (client != null) { client.close(); } if (context != null) { context.close(); } }
		finally {
			if (schema != null) { try (var connection = connection(); var statement = connection.createStatement()) { statement.execute("DROP SCHEMA " + schema + " CASCADE"); } }
		}
		assertThat(output.getAll()).doesNotContain(PASSWORD, PHONE, CORRECTION, "PrivatePatient", "PrivateSurname", "private-broker-diagnostic", FinalUserJwtTestSupport.signingSecret());
		for (String issued : tokens) { assertThat(output.getAll()).doesNotContain(issued); }
	}

	@Test
	void requestSubmissionDuplicateCorrectionAndFinalConfirmationAreObservableThroughOwnedHttpProgress() throws Exception {
		assertProgress(get(id, token), 200, "WAITING_FOR_PHONE", "NONE");
		phoneRequested();
		assertProgress(get(id, token), 200, "WAITING_FOR_PHONE", "SUBMIT_PHONE");
		String other = registerAndLogin("bob");
		assertProblem(get(id, other), 404, "RESERVATION_PROCESS_NOT_FOUND");
		assertProblem(post(phonePath(id), other, body(PHONE)), 404, "RESERVATION_PROCESS_NOT_FOUND");
		assertProblem(post(phonePath(id), token, "{\"phoneNumber\":\"" + PHONE + "\",\"userId\":1}"), 400, "INVALID_REQUEST");
		assertProblem(post(phonePath(id), token, body("123")), 400, "INVALID_PHONE_NUMBER");
		assertThat(sent).isEmpty();
		assertProgress(post(phonePath(id), token, body("00 (000) 001-234")), 202, "WAITING_FOR_CONFIRMATION", "NONE");
		assertThat(sent).hasSize(1);
		var first = json.readTree(sent.getFirst().payload());
		assertThat(sent.getFirst().topic()).isEqualTo("test.appointment.phone");
		assertThat(sent.getFirst().key()).isEqualTo(PROCESS);
		assertThat(first.get("requestEventId").asText()).isEqualTo("workflow-request");
		assertThat(first.at("/information/phoneNumber").asText()).isEqualTo(PHONE);
		assertProgress(post(phonePath(id), token, body(PHONE)), 202, "WAITING_FOR_CONFIRMATION", "NONE");
		assertThat(sent).hasSize(1);
		deliver(outcome("AdditionalInformationRejected", "workflow-rejection"));
		assertProgress(get(id, token), 200, "WAITING_FOR_PHONE", "CORRECT_PHONE");
		assertProgress(post(phonePath(id), token, body(CORRECTION)), 202, "WAITING_FOR_CONFIRMATION", "NONE");
		assertThat(sent).hasSize(2);
		var corrected = json.readTree(sent.getLast().payload());
		assertThat(corrected.get("eventId").asText()).isNotEqualTo(first.get("eventId").asText());
		assertThat(corrected.get("requestEventId").asText()).isEqualTo("workflow-request");
		var confirmed = outcome("AppointmentConfirmed", "workflow-final"); deliver(confirmed);
		assertProgress(get(id, token), 200, "CONFIRMED", "NONE");
		long version = context.getBean(ReservationProcessJpaRepository.class).findById(id).orElseThrow().getVersion();
		deliver(confirmed);
		assertThat(context.getBean(ReservationProcessJpaRepository.class).findById(id).orElseThrow().getVersion()).isEqualTo(version);
		assertProblem(post(phonePath(id), token, body(CORRECTION)), 409, "PHONE_SUBMISSION_CONFLICT");
		assertThat(sent).hasSize(2);
		verifyNoInteractions(context.getBean(CurrentProfessionalSource.class));
	}

	@ParameterizedTest
	@ValueSource(strings = {"AppointmentConfirmed", "AppointmentProcessExpired", "AppointmentProcessInvalid"})
	void authoritativeEventDuringPublicationWinsOverTheBrokerAcknowledgment(String type) throws Exception {
		phoneRequested();
		duringSend.set(() -> deliver(outcome(type, "during-send")));
		String status = switch (type) { case "AppointmentConfirmed" -> "CONFIRMED"; case "AppointmentProcessExpired" -> "EXPIRED"; default -> "INVALID"; };
		assertProgress(post(phonePath(id), token, body(PHONE)), 202, status, "NONE");
		assertProgress(get(id, token), 200, status, "NONE");
		assertThat(sent).hasSize(1);
	}

	@Test
	void uncertainPublicationSurvivesRestartAndRepublishesExactlyTheSameEnvelope() throws Exception {
		phoneRequested(); failBroker = true;
		assertProgress(post(phonePath(id), token, body(PHONE)), 202, "PHONE_SUBMISSION_PENDING", "NONE");
		assertThat(sent).hasSize(1);
		String original = sent.getFirst().payload();
		context.close(); failBroker = false; startContext();
		assertThat(context.getBean(SubmitPhoneInformationUseCase.class).recover(10).published()).isEqualTo(1);
		assertThat(sent).hasSize(2);
		assertThat(sent.getLast().payload()).isEqualTo(original);
		assertProgress(get(id, token), 200, "WAITING_FOR_CONFIRMATION", "NONE");
		deliver(outcome("AppointmentConfirmed", "after-restart"));
		assertProgress(get(id, token), 200, "CONFIRMED", "NONE");
	}

	private void phoneRequested() {
		var event = json.createObjectNode().put("schemaVersion", 1).put("eventId", "workflow-request")
				.put("eventType", "AdditionalInformationRequested").put("reservationProcessId", PROCESS).put("holdId", HOLD)
				.put("requiredField", "PHONE_NUMBER").put("message", "Provide a phone number").put("expiresAt", expiry.toString()).put("occurredAt", Instant.now().toString());
		deliver(event);
	}
	private JsonNode outcome(String type, String eventId) {
		var event = json.createObjectNode().put("schemaVersion", 1).put("eventId", eventId).put("eventType", type)
				.put("groupId", "test-project").put("reservationProcessId", PROCESS).put("holdId", HOLD)
				.put("requestEventId", "workflow-request").put("occurredAt", Instant.now().toString());
		switch (type) {
			case "AppointmentConfirmed" -> event.put("status", "CONFIRMED").put("reservationId", 8251).put("professionalId", 202).put("date", "2030-01-03").put("startTime", "18:00:00");
			case "AdditionalInformationRejected" -> event.put("reason", "INVALID_PHONE_NUMBER").put("message", "Correct the phone").put("expiresAt", expiry.toString());
			case "AppointmentProcessExpired" -> event.put("reason", "HOLD_EXPIRED");
			default -> event.put("reason", "REQUEST_EVENT_MISMATCH");
		}
		return event;
	}
	private void deliver(JsonNode event) {
		context.getBean(CatedraAppointmentActionListener.class).receive(new ConsumerRecord<>("test.appointment.actions", 0, 1, PROCESS, json.writeValueAsString(event)));
	}
	private void assertProgress(HttpResponse<String> response, int status, String phase, String action) {
		assertThat(response.statusCode()).isEqualTo(status);
		assertSafe(response);
		var node = json.readTree(response.body()); assertThat(node.size()).isEqualTo(5);
		assertThat(node.get("id").asLong()).isEqualTo(id);
		assertThat(node.get("status").asText()).isEqualTo(phase); assertThat(node.get("requiredAction").asText()).isEqualTo(action);
	}
	private void assertProblem(HttpResponse<String> response, int status, String code) {
		assertThat(response.statusCode()).isEqualTo(status); assertSafe(response);
		assertThat(json.readTree(response.body()).get("code").asText()).isEqualTo(code);
	}
	private void assertSafe(HttpResponse<String> response) {
		assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
		assertThat(response.headers().firstValue("Set-Cookie")).isEmpty();
		assertThat(response.body()).doesNotContain(PHONE, CORRECTION, PASSWORD, PROCESS, HOLD, "workflow-request", "PrivatePatient", "PrivateSurname", "not-a-real-catedra-token");
	}
	private String registerAndLogin(String login) throws Exception {
		assertThat(post("/api/register", null, json.writeValueAsString(Map.of("login", login, "password", PASSWORD, "email", login + "@example.test"))).statusCode()).isEqualTo(201);
		var response = post("/api/authenticate", null, json.writeValueAsString(Map.of("username", login, "password", PASSWORD)));
		assertThat(response.statusCode()).isEqualTo(200);
		String issued = json.readTree(response.body()).get("id_token").asText(); tokens.add(issued); return issued;
	}
	private HttpResponse<String> get(long localId, String bearer) throws Exception {
		return client.send(HttpRequest.newBuilder(URI.create(baseUrl + "/api/reservation-processes/" + localId)).timeout(Duration.ofSeconds(15))
				.header("Authorization", "Bearer " + bearer).GET().build(), HttpResponse.BodyHandlers.ofString());
	}
	private HttpResponse<String> post(String path, String bearer, String body) throws Exception {
		var request = HttpRequest.newBuilder(URI.create(baseUrl + path)).timeout(Duration.ofSeconds(15)).header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(body));
		if (bearer != null) { request.header("Authorization", "Bearer " + bearer); }
		return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
	}
	private String phonePath(long localId) { return "/api/reservation-processes/" + localId + "/phone"; }
	private static String body(String phone) { return "{\"phoneNumber\":\"" + phone + "\"}"; }
	private Connection connection() throws SQLException { return DriverManager.getConnection(url, username, password); }
	private static String required(String key) {
		String value = System.getenv(key); if (value == null || value.isBlank()) { throw new IllegalStateException("Set " + key + " for postgres-integration"); } return value;
	}
	private record Sent(String topic, String key, String payload) { @Override public String toString() { return "Sent[redacted]"; } }
	@TestConfiguration(proxyBeanMethods = false)
	static class ControlledTransport {
		@Bean @Primary @SuppressWarnings("unchecked") KafkaTemplate<String, String> controlledKafka() { return mock(KafkaTemplate.class); }
		@Bean @Primary CurrentProfessionalSource controlledCatalog() { return mock(CurrentProfessionalSource.class); }
	}
}
