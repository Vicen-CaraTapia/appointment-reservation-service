package ar.edu.um.prog2.appointmentreservation.reservation.infrastructure.web;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.MapPropertySource;
import tools.jackson.databind.json.JsonMapper;

import ar.edu.um.prog2.appointmentreservation.AppointmentReservationApplication;
import ar.edu.um.prog2.appointmentreservation.professional.application.model.CurrentProfessional;
import ar.edu.um.prog2.appointmentreservation.professional.application.port.out.CurrentProfessionalSource;
import ar.edu.um.prog2.appointmentreservation.reservation.domain.model.ReservationProcessStatus;
import ar.edu.um.prog2.appointmentreservation.reservation.infrastructure.persistence.entity.ReservationProcessEntity;
import ar.edu.um.prog2.appointmentreservation.reservation.infrastructure.persistence.repository.ReservationProcessJpaRepository;
import ar.edu.um.prog2.appointmentreservation.support.CatedraIntegrationTestSupport;
import ar.edu.um.prog2.appointmentreservation.support.CatalogIntegrationTestSupport;
import ar.edu.um.prog2.appointmentreservation.support.FinalUserJwtTestSupport;
import ar.edu.um.prog2.appointmentreservation.user.infrastructure.persistence.repository.UserJpaRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Real HTTP, JWT, application, JPA and PostgreSQL; only external services are controlled. */
@ExtendWith(OutputCaptureExtension.class)
class ReservationProcessConfirmationHttpIT {

	private static final String PASSWORD = "test-only-http-confirmation-password";
	private static final String TECHNICAL_JWT = "test-only-http-catedra-credential";
	private static final String FIRST_NAME = "PrivatePatientForHttp";
	private static final String LAST_NAME = "PrivateSurnameForHttp";
	private static final String PRIVATE_DETAIL = "private-remote-or-rejected-input";
	private static final String PATIENT_JSON = "{\"patientFirstName\":\"" + FIRST_NAME + "\",\"patientLastName\":\"" + LAST_NAME + "\"}";
	private static final LocalDate DATE = LocalDate.of(2030, 1, 3);
	private final AtomicInteger holdPosts = new AtomicInteger();
	private final AtomicInteger confirmationPosts = new AtomicInteger();
	private final AtomicReference<String> remoteAuthorization = new AtomicReference<>();
	private final AtomicReference<String> remotePatient = new AtomicReference<>();
	private final CountDownLatch remoteEntered = new CountDownLatch(1);
	private final CountDownLatch remoteRelease = new CountDownLatch(1);
	private final List<String> tokens = new ArrayList<>();
	private volatile boolean blockConfirmation;
	private volatile int remoteStatus = 202;
	private volatile String remoteCode = "UNCLASSIFIED";
	private String url;
	private String username;
	private String password;
	private String schema;
	private ConfigurableApplicationContext context;
	private HttpServer catedra;
	private HttpClient client;
	private JsonMapper json;
	private String baseUrl;
	private String token;
	private long ownerId;
	private CurrentProfessionalSource catalog;
	private ReservationProcessJpaRepository processes;
	private Instant holdExpiry;
	private Instant acceptedExpiry;

	@BeforeEach
	void startRealHttpApplicationWithAnIsolatedMigratedSchema() throws Exception {
		url = requiredEnvironment("APPOINTMENT_TEST_DB_URL");
		username = requiredEnvironment("APPOINTMENT_TEST_DB_USERNAME");
		password = requiredEnvironment("APPOINTMENT_TEST_DB_PASSWORD");
		try (var connection = connection(); var statement = connection.createStatement()) {
			try (var result = statement.executeQuery("SELECT current_database()")) {
				result.next();
				assertThat(result.getString(1)).startsWith("appointment_");
			}
			String candidate = "appointment_it_" + UUID.randomUUID().toString().replace("-", "");
			statement.execute("CREATE SCHEMA " + candidate);
			schema = candidate;
		}
		holdExpiry = Instant.now().plusSeconds(300).truncatedTo(ChronoUnit.MICROS);
		acceptedExpiry = holdExpiry.plusSeconds(300).plusNanos(789);
		catedra = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		catedra.createContext("/", this::catedraRequest);
		catedra.start();
		Map<String, Object> properties = new HashMap<>();
		properties.put(FinalUserJwtTestSupport.SECRET_PROPERTY, FinalUserJwtTestSupport.signingSecret());
		properties.put("spring.datasource.url", url);
		properties.put("spring.datasource.username", username);
		properties.put("spring.datasource.password", password);
		properties.put("spring.flyway.schemas", schema);
		properties.put("spring.flyway.default-schema", schema);
		properties.put("spring.flyway.create-schemas", false);
		properties.put("spring.jpa.properties.hibernate.default_schema", schema);
		properties.put("spring.jpa.hibernate.ddl-auto", "validate");
		properties.put("catedra.rest.base-url", "http://127.0.0.1:" + catedra.getAddress().getPort());
		properties.put("catedra.rest.connect-timeout", "2s");
		properties.put("catedra.rest.read-timeout", "10s");
		properties.put("catedra.technical-jwt", TECHNICAL_JWT);
		properties.put("server.port", 0);
		context = new SpringApplicationBuilder(AppointmentReservationApplication.class, ControlledCatalog.class)
				.initializers(CatedraIntegrationTestSupport::configure, CatalogIntegrationTestSupport::configure)
				.initializers(application -> application.getEnvironment().getPropertySources().addFirst(
						new MapPropertySource("confirmationHttpTest", properties)))
				.web(WebApplicationType.SERVLET).run();
		baseUrl = "http://127.0.0.1:" + context.getEnvironment().getProperty("local.server.port");
		json = context.getBean(JsonMapper.class);
		client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
		catalog = context.getBean(CurrentProfessionalSource.class);
		when(catalog.load(202)).thenReturn(new CurrentProfessional(202, "Martin", "Rivas", true,
				new CurrentProfessional.Category(101, "Cardiologia", true),
				List.of(new CurrentProfessional.WeeklySchedule(304, DATE.getDayOfWeek(), LocalTime.of(18, 0), LocalTime.of(21, 0), 30, true))));
		processes = context.getBean(ReservationProcessJpaRepository.class);
		token = registerAndLogin("alice");
		ownerId = context.getBean(UserJpaRepository.class).findByLoginIgnoreCase("alice").orElseThrow().getId();
	}

	@AfterEach
	void closeResourcesDropOnlyThisSchemaAndVerifySafeLogs(CapturedOutput output) throws Exception {
		remoteRelease.countDown();
		try {
			if (client != null) { client.close(); }
			if (context != null) { context.close(); }
		} finally {
			if (catedra != null) { catedra.stop(0); }
			if (schema != null) {
				try (var connection = connection(); var statement = connection.createStatement()) {
					statement.execute("DROP SCHEMA " + schema + " CASCADE");
				}
			}
		}
		assertThat(output.getAll()).doesNotContain(PASSWORD, TECHNICAL_JWT, FIRST_NAME, LAST_NAME,
				PRIVATE_DETAIL, FinalUserJwtTestSupport.signingSecret(), "Failing row contains", "test_reject_accepted_save");
		for (String issued : tokens) { assertThat(output.getAll()).doesNotContain(issued); }
	}

	@Test
	void ownerAcceptsAndPersistsAuthoritativeExpiryAndRepeatDoesNotResend() throws Exception {
		long id = startOwnedProcess();
		var response = confirm(id, token, PATIENT_JSON);
		assertAccepted(response, id, "WAITING_FOR_PHONE", acceptedExpiry.truncatedTo(ChronoUnit.MICROS));
		var saved = process(id);
		assertThat(saved.getStatus()).isEqualTo(ReservationProcessStatus.WAITING_FOR_PHONE);
		assertThat(saved.getVersion()).isEqualTo(2);
		assertThat(saved.getOwner().getId()).isEqualTo(ownerId);
		assertThat(saved.getExternalPatientId()).isEqualTo("user-" + ownerId);
		assertThat(saved.getHoldId()).isEqualTo("test-hold");
		assertThat(saved.getExternalReservationProcessId()).isEqualTo("test-process");
		assertThat(saved.getPatientFirstName()).isEqualTo(FIRST_NAME);
		assertThat(saved.getPatientLastName()).isEqualTo(LAST_NAME);
		assertThat(saved.getLastModifiedBy()).isEqualTo("alice");
		assertThat(saved.getLastModifiedDate()).isNotNull();
		assertThat(remoteAuthorization.get()).isEqualTo("Bearer " + TECHNICAL_JWT);
		assertThat(json.readTree(remotePatient.get()).get("externalPatientId").asString()).isEqualTo("user-" + ownerId);
		assertThat(json.readTree(remotePatient.get()).get("patientFirstName").asString()).isEqualTo(FIRST_NAME);
		assertThat(confirm(id, token, PATIENT_JSON).body()).isEqualTo(response.body());
		assertThat(confirmationPosts).hasValue(1);
		assertThat(holdPosts).hasValue(1);
	}

	@Test
	void aSecondHttpClickSeesTheCommittedClaimWhileTheFirstRemotePostIsInFlight() throws Exception {
		long id = startOwnedProcess();
		blockConfirmation = true;
		var first = client.sendAsync(request(path(id), token, PATIENT_JSON), HttpResponse.BodyHandlers.ofString());
		try {
			assertThat(remoteEntered.await(5, TimeUnit.SECONDS)).isTrue();
			assertThat(process(id).getStatus()).isEqualTo(ReservationProcessStatus.CONFIRMATION_PENDING);
			assertAccepted(confirm(id, token, PATIENT_JSON), id, "CONFIRMATION_PENDING", holdExpiry);
			assertThat(confirmationPosts).hasValue(1);
		} finally {
			remoteRelease.countDown();
		}
		assertAccepted(first.get(10, TimeUnit.SECONDS), id, "WAITING_FOR_PHONE", acceptedExpiry.truncatedTo(ChronoUnit.MICROS));
		assertThat(confirmationPosts).hasValue(1);
		assertThat(process(id).getVersion()).isEqualTo(2);
	}

	@Test
	void anotherUsersProcessAndAnAbsentProcessHaveTheSame404WithoutSideEffects() throws Exception {
		long id = startOwnedProcess();
		String otherToken = registerAndLogin("bob");
		var foreign = post(path(id) + "?userId=" + ownerId + "&ownerUserId=" + ownerId,
				otherToken, PATIENT_JSON);
		var absent = confirm(Long.MAX_VALUE, otherToken, PATIENT_JSON);
		assertProblem(foreign, 404, "RESERVATION_PROCESS_NOT_FOUND");
		assertProblem(absent, 404, "RESERVATION_PROCESS_NOT_FOUND");
		assertThat(json.readTree(foreign.body()).get("detail")).isEqualTo(json.readTree(absent.body()).get("detail"));
		assertUnchangedHeld(id);
	}

	@Test
	void validationAndAdministrativeInputAreRejectedWithoutMutationOrExternalRequests() throws Exception {
		long id = startOwnedProcess();
		assertProblem(confirm(id, token, "{}"), 400, "VALIDATION_ERROR");
		assertProblem(confirm(id, token, "{\"patientFirstName\":\"" + PRIVATE_DETAIL.repeat(5)
				+ "\",\"patientLastName\":\"Perez\"}"), 400, "VALIDATION_ERROR");
		assertProblem(confirm(id, token, PATIENT_JSON.substring(0, PATIENT_JSON.length() - 1)
				+ ",\"ownerUserId\":999}"), 400, "INVALID_REQUEST");
		assertProblem(post("/api/reservation-processes/0/confirm", token, PATIENT_JSON), 400, "INVALID_REQUEST");
		assertUnchangedHeld(id);
	}

	@Test
	void missingFinalUserTokenAndCatedraCredentialCannotConfirm() throws Exception {
		long id = startOwnedProcess();
		assertProblem(confirm(id, null, PATIENT_JSON), 401, "UNAUTHORIZED");
		assertProblem(confirm(id, TECHNICAL_JWT, PATIENT_JSON), 401, "UNAUTHORIZED");
		assertUnchangedHeld(id);
	}

	@ParameterizedTest
	@ValueSource(ints = {401, 403})
	void technicalAuthenticationErrorsAre502AndRetainTheAttempt(int remoteStatus) throws Exception {
		long id = startOwnedProcess();
		this.remoteStatus = remoteStatus;
		assertProblem(confirm(id, token, PATIENT_JSON), 502, "RESERVATION_DEPENDENCY_ERROR");
		assertPendingWithoutResend(id);
	}

	@ParameterizedTest
	@ValueSource(strings = {"HOLD_EXPIRED", "SLOT_ALREADY_RESERVED"})
	void definitiveRemoteRejectionsReleaseOnlyTheAttemptWithoutSavingAcceptance(String code) throws Exception {
		long id = startOwnedProcess();
		remoteStatus = "HOLD_EXPIRED".equals(code) ? 404 : 409;
		remoteCode = code;
		assertProblem(confirm(id, token, PATIENT_JSON), remoteStatus, code);
		var saved = process(id);
		assertThat(saved.getStatus()).isEqualTo(ReservationProcessStatus.HELD);
		assertThat(saved.getVersion()).isEqualTo(2);
		assertThat(saved.getPatientFirstName()).isNull();
		assertThat(saved.getPatientLastName()).isNull();
		assertThat(saved.getExpiresAt()).isEqualTo(holdExpiry);
		assertThat(confirmationPosts).hasValue(1);
	}

	@Test
	void unavailableRemoteOutcomeRemainsPendingAndRepeatNeverResends() throws Exception {
		long id = startOwnedProcess();
		remoteStatus = 503;
		var response = confirm(id, token, PATIENT_JSON);
		assertProblem(response, 503, "CONFIRMATION_OUTCOME_UNKNOWN");
		assertThat(response.body()).contains("Do not automatically repeat");
		assertPendingWithoutResend(id);
	}

	@Test
	void localExpirationRejectsConfirmationBeforeCatalogOrRemoteRequests() throws Exception {
		long id = startOwnedProcess();
		try (var connection = connection(); var statement = connection.prepareStatement(
				"UPDATE " + schema + ".reservation_process SET expires_at = ? WHERE id = ?")) {
			statement.setObject(1, java.time.OffsetDateTime.now().minusMinutes(1));
			statement.setLong(2, id);
			assertThat(statement.executeUpdate()).isEqualTo(1);
		}
		assertProblem(confirm(id, token, PATIENT_JSON), 404, "HOLD_EXPIRED");
		assertUnchangedHeld(id);
	}

	@Test
	void changingThePatientAfterAcceptanceConflictsWithoutAnotherPost() throws Exception {
		long id = startOwnedProcess();
		assertThat(confirm(id, token, PATIENT_JSON).statusCode()).isEqualTo(202);
		var before = process(id);
		assertProblem(confirm(id, token, "{\"patientFirstName\":\"Another\",\"patientLastName\":\"Patient\"}"),
				409, "RESERVATION_PROCESS_CONFLICT");
		var after = process(id);
		assertThat(after.getVersion()).isEqualTo(before.getVersion());
		assertThat(after.getPatientFirstName()).isEqualTo(FIRST_NAME);
		assertThat(confirmationPosts).hasValue(1);
	}

	@Test
	void localSaveFailureAfterRemoteAcceptanceLeavesADurableClaimAndSafeHttpError() throws Exception {
		long id = startOwnedProcess();
		// Only this disposable schema is changed; no production migration is altered.
		try (var connection = connection(); var statement = connection.createStatement()) {
			statement.execute("ALTER TABLE " + schema + ".reservation_process ADD CONSTRAINT test_reject_accepted_save "
					+ "CHECK (process_status <> 'WAITING_FOR_PHONE')");
		}
		var response = confirm(id, token, PATIENT_JSON);
		assertProblem(response, 500, "CONFIRMATION_PERSISTENCE_FAILED");
		assertThat(response.body()).contains("Remote acceptance may exist", "Do not automatically repeat");
		assertThat(process(id).getExpiresAt()).isEqualTo(holdExpiry);
		assertPendingWithoutResend(id);
	}

	private long startOwnedProcess() throws Exception {
		var response = post("/api/reservation-processes", token,
				"{\"professionalId\":202,\"date\":\"" + DATE + "\",\"startTime\":\"18:00\"}");
		assertThat(response.statusCode()).isEqualTo(201);
		long id = json.readTree(response.body()).get("id").asLong();
		assertThat(id).isPositive();
		clearInvocations(catalog);
		return id;
	}

	private void assertUnchangedHeld(long id) {
		var saved = process(id);
		assertThat(saved.getStatus()).isEqualTo(ReservationProcessStatus.HELD);
		assertThat(saved.getVersion()).isZero();
		assertThat(saved.getPatientFirstName()).isNull();
		assertThat(saved.getPatientLastName()).isNull();
		assertThat(confirmationPosts).hasValue(0);
		assertThat(holdPosts).hasValue(1);
		verifyNoInteractions(catalog);
	}

	private void assertPendingWithoutResend(long id) throws Exception {
		var saved = process(id);
		assertThat(saved.getStatus()).isEqualTo(ReservationProcessStatus.CONFIRMATION_PENDING);
		assertThat(saved.getVersion()).isEqualTo(1);
		assertThat(saved.getPatientFirstName()).isEqualTo(FIRST_NAME);
		assertThat(saved.getPatientLastName()).isEqualTo(LAST_NAME);
		assertAccepted(confirm(id, token, PATIENT_JSON), id, "CONFIRMATION_PENDING", holdExpiry);
		assertThat(process(id).getVersion()).isEqualTo(1);
		assertThat(confirmationPosts).hasValue(1);
	}

	private void assertAccepted(HttpResponse<String> response, long id, String status, Instant expiresAt) {
		assertThat(response.statusCode()).isEqualTo(202);
		assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
		assertThat(response.headers().firstValue("Set-Cookie")).isEmpty();
		assertThat(response.headers().firstValue("Authorization")).isEmpty();
		assertThat(response.headers().firstValue("Content-Type").orElseThrow()).startsWith("application/json");
		var body = json.readTree(response.body());
		assertThat(body.size()).isEqualTo(3);
		assertThat(body.get("id").asLong()).isEqualTo(id);
		assertThat(body.get("status").asString()).isEqualTo(status);
		assertThat(body.get("expiresAt").asString()).isEqualTo(expiresAt.toString());
		assertSafeBody(response.body());
	}

	private void assertProblem(HttpResponse<String> response, int status, String code) {
		assertThat(response.statusCode()).isEqualTo(status);
		assertThat(json.readTree(response.body()).get("code").asString()).isEqualTo(code);
		assertThat(response.headers().firstValue("Content-Type").orElseThrow()).startsWith("application/problem+json");
		assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
		assertThat(response.headers().firstValue("Set-Cookie")).isEmpty();
		assertThat(response.headers().firstValue("Retry-After")).isEmpty();
		if (status == 401) { assertThat(response.headers().firstValue("WWW-Authenticate")).contains("Bearer"); }
		else { assertThat(response.headers().firstValue("WWW-Authenticate")).isEmpty(); }
		assertSafeBody(response.body());
	}

	private void assertSafeBody(String body) {
		assertThat(body).doesNotContain(PASSWORD, TECHNICAL_JWT, FIRST_NAME, LAST_NAME, PRIVATE_DETAIL,
				"test-hold", "test-process", "test_reject_accepted_save", "Failing row", "SQL", "Exception");
		for (String issued : tokens) { assertThat(body).doesNotContain(issued); }
	}

	private String registerAndLogin(String login) throws Exception {
		assertThat(post("/api/register", null, json.writeValueAsString(Map.of("login", login,
				"password", PASSWORD, "email", login + "@example.test"))).statusCode()).isEqualTo(201);
		var response = post("/api/authenticate", null, json.writeValueAsString(Map.of("username", login, "password", PASSWORD)));
		assertThat(response.statusCode()).isEqualTo(200);
		String issued = json.readTree(response.body()).get("id_token").asString();
		tokens.add(issued);
		return issued;
	}

	private HttpResponse<String> confirm(long id, String bearer, String body) throws Exception {
		return post(path(id), bearer, body);
	}

	private HttpResponse<String> post(String path, String bearer, String body) throws Exception {
		return client.send(request(path, bearer, body), HttpResponse.BodyHandlers.ofString());
	}

	private HttpRequest request(String path, String bearer, String body) {
		var builder = HttpRequest.newBuilder(URI.create(baseUrl + path)).timeout(Duration.ofSeconds(15))
				.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body));
		if (bearer != null) { builder.header("Authorization", "Bearer " + bearer); }
		return builder.build();
	}

	private static String path(long id) { return "/api/reservation-processes/" + id + "/confirm"; }
	private ReservationProcessEntity process(long id) { return processes.findById(id).orElseThrow(); }
	private Connection connection() throws SQLException { return DriverManager.getConnection(url, username, password); }

	private void catedraRequest(HttpExchange exchange) throws IOException {
		try (exchange) {
			if (!"POST".equals(exchange.getRequestMethod())) {
				respond(exchange, 405, "application/problem+json", "{}");
			} else if ("/api/appointment-holds".equals(exchange.getRequestURI().getPath())) {
				holdPosts.incrementAndGet();
				exchange.getRequestBody().readAllBytes();
				respond(exchange, 201, "application/json", "{\"holdId\":\"test-hold\",\"reservationProcessId\":\"test-process\","
						+ "\"status\":\"HELD\",\"expiresAt\":\"" + holdExpiry + "\"}");
			} else if ("/api/appointment-holds/test-hold/confirm".equals(exchange.getRequestURI().getPath())) {
				remoteAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
				remotePatient.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
				confirmationPosts.incrementAndGet();
				remoteEntered.countDown();
				if (blockConfirmation) {
					try {
						if (!remoteRelease.await(10, TimeUnit.SECONDS)) {
							respond(exchange, 503, "application/problem+json", "{}");
							return;
						}
					} catch (InterruptedException failure) {
						Thread.currentThread().interrupt();
						return;
					}
				}
				if (remoteStatus == 202) {
					respond(exchange, 202, "application/json", "{\"reservationProcessId\":\"test-process\","
							+ "\"status\":\"WAITING_FOR_PHONE\",\"expiresAt\":\"" + acceptedExpiry + "\"}");
				} else {
					// Deliberately contradictory detail: decisions must use HTTP status/code only.
					respond(exchange, remoteStatus, "application/problem+json", "{\"status\":200,\"code\":\"" + remoteCode
							+ "\",\"detail\":\"HOLD_EXPIRED " + PRIVATE_DETAIL + " " + TECHNICAL_JWT + "\"}");
				}
			} else {
				respond(exchange, 404, "application/problem+json", "{}");
			}
		}
	}

	private static void respond(HttpExchange exchange, int status, String type, String body) throws IOException {
		byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().set("Content-Type", type);
		exchange.sendResponseHeaders(status, bytes.length);
		exchange.getResponseBody().write(bytes);
	}

	private static String requiredEnvironment(String name) {
		String value = System.getenv(name);
		if (value == null || value.isBlank()) { throw new IllegalStateException("Set " + name + " for postgres-integration"); }
		return value;
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class ControlledCatalog {
		@Bean
		@Primary
		CurrentProfessionalSource controlledProfessionalSource() { return mock(CurrentProfessionalSource.class); }
	}
}
