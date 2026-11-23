package ar.edu.um.prog2.appointmentreservation.reservation.infrastructure.web;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import tools.jackson.databind.json.JsonMapper;

import ar.edu.um.prog2.appointmentreservation.AppointmentReservationApplication;
import ar.edu.um.prog2.appointmentreservation.reservation.application.ExternalPatientId;
import ar.edu.um.prog2.appointmentreservation.reservation.domain.model.ReservationProcessStatus;
import ar.edu.um.prog2.appointmentreservation.reservation.infrastructure.persistence.entity.ReservationProcessEntity;
import ar.edu.um.prog2.appointmentreservation.reservation.infrastructure.persistence.repository.ReservationProcessJpaRepository;
import ar.edu.um.prog2.appointmentreservation.support.CatedraIntegrationTestSupport;
import ar.edu.um.prog2.appointmentreservation.support.CatalogIntegrationTestSupport;
import ar.edu.um.prog2.appointmentreservation.support.FinalUserJwtTestSupport;
import ar.edu.um.prog2.appointmentreservation.user.infrastructure.persistence.entity.UserEntity;
import ar.edu.um.prog2.appointmentreservation.user.infrastructure.persistence.repository.UserJpaRepository;
import ar.edu.um.prog2.appointmentreservation.user.infrastructure.security.FinalUserJwtProperties;
import ar.edu.um.prog2.appointmentreservation.user.infrastructure.security.FinalUserPrincipal;
import ar.edu.um.prog2.appointmentreservation.user.infrastructure.security.NimbusFinalUserTokenIssuer;

import static org.assertj.core.api.Assertions.assertThat;

/** Real HTTP, signed final-user JWT, Appointment PostgreSQL and cátedra REST contract stub. */
@ExtendWith(OutputCaptureExtension.class)
class OwnedReservationsHttpIT {
	private static final String TECHNICAL_JWT = "test-only-technical-catedra-credential";
	private static final String ARGON_HASH = "$argon2id$v=19$m=65536,t=3,p=1$c2FsdA$SGFzaA";
	private static final LocalDate FIRST_DATE = LocalDate.of(2030, 1, 10);
	private static final LocalDate SECOND_DATE = LocalDate.of(2030, 1, 11);
	private static final Instant CREATED = Instant.parse("2030-01-01T12:00:00Z");
	private final AtomicInteger listRequests = new AtomicInteger();
	private final AtomicInteger cancellationRequests = new AtomicInteger();
	private final AtomicInteger catedraListStatus = new AtomicInteger(200);
	private final AtomicInteger catedraCancellationStatus = new AtomicInteger(200);
	private final AtomicReference<String> catedraAuthorization = new AtomicReference<>();
	private final AtomicReference<String> cancellationBody = new AtomicReference<>();
	private final List<Row> remoteRows = new CopyOnWriteArrayList<>();
	private final List<String> issuedTokens = new ArrayList<>();
	private String url;
	private String username;
	private String password;
	private String schema;
	private HttpServer catedra;
	private ConfigurableApplicationContext context;
	private HttpClient client;
	private JsonMapper json;
	private String baseUrl;
	private UserJpaRepository users;
	private ReservationProcessJpaRepository processes;
	private UserEntity alice;
	private UserEntity bob;
	private String aliceToken;
	private String bobToken;
	private long aliceFirstProcessId;
	private long aliceSecondProcessId;
	private long bobProcessId;

	@BeforeEach
	void startApplicationWithAnIsolatedMigratedSchemaAndCatedraStub() throws Exception {
		url = requiredEnvironment("APPOINTMENT_TEST_DB_URL");
		username = requiredEnvironment("APPOINTMENT_TEST_DB_USERNAME");
		password = requiredEnvironment("APPOINTMENT_TEST_DB_PASSWORD");
		try (var connection = connection(); var statement = connection.createStatement()) {
			try (var result = statement.executeQuery("SELECT current_database()")) {
				result.next();
				assertThat(result.getString(1)).startsWith("appointment_");
			}
			schema = "appointment_owned_reservations_" + UUID.randomUUID().toString().replace("-", "");
			statement.execute("CREATE SCHEMA " + schema);
		}
		catedra = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		catedra.createContext("/", this::catedraRequest);
		catedra.start();
		var properties = new HashMap<String, Object>();
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
		properties.put("catedra.rest.read-timeout", "5s");
		properties.put("catedra.technical-jwt", TECHNICAL_JWT);
		properties.put("server.port", 0);
		context = new SpringApplicationBuilder(AppointmentReservationApplication.class)
				.initializers(CatedraIntegrationTestSupport::configure, CatalogIntegrationTestSupport::configure)
				.initializers(application -> application.getEnvironment().getPropertySources().addFirst(
						new MapPropertySource("ownedReservationsHttpTest", properties)))
				.web(WebApplicationType.SERVLET).run();
		baseUrl = "http://127.0.0.1:" + context.getEnvironment().getProperty("local.server.port");
		client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
		json = context.getBean(JsonMapper.class);
		users = context.getBean(UserJpaRepository.class);
		processes = context.getBean(ReservationProcessJpaRepository.class);
		alice = createUser("alice", "alice@example.test");
		bob = createUser("bob", "bob@example.test");
		aliceToken = token(alice, "alice");
		bobToken = token(bob, "bob");
		aliceFirstProcessId = createProcess(alice, "alice-process-1", "alice-hold-1", FIRST_DATE, LocalTime.of(9, 30));
		aliceSecondProcessId = createProcess(alice, "alice-process-2", "alice-hold-2", SECOND_DATE, LocalTime.of(10, 0));
		bobProcessId = createProcess(bob, "bob-process-1", "bob-hold-1", FIRST_DATE, LocalTime.of(11, 30));
		remoteRows.add(new Row(8251, "alice-process-1", patientId(alice), FIRST_DATE, LocalTime.of(9, 30),
				LocalTime.of(10, 0), 201, TechnicalStatus.CONFIRMED));
		remoteRows.add(new Row(8252, "alice-process-2", patientId(alice), SECOND_DATE, LocalTime.of(10, 0),
				LocalTime.of(10, 30), 202, TechnicalStatus.CONFIRMED));
		remoteRows.add(new Row(8253, "bob-process-1", patientId(bob), FIRST_DATE, LocalTime.of(11, 30),
				LocalTime.NOON, 203, TechnicalStatus.CONFIRMED));
	}

	@AfterEach
	void closeApplicationAndRemoveOnlyTheTestSchema(CapturedOutput output) throws Exception {
		if (context != null) { context.close(); }
		if (catedra != null) { catedra.stop(0); }
		if (schema != null) {
			try (var connection = connection(); var statement = connection.createStatement()) {
				statement.execute("DROP SCHEMA " + schema + " CASCADE");
			}
		}
		for (String issued : issuedTokens) { assertThat(output.getAll()).doesNotContain(issued); }
		assertThat(output.getAll()).doesNotContain(TECHNICAL_JWT);
	}

	@Test
	void listsOnlyTheAuthenticatedUsersReservationsWithExternalFiltersAndLocalPagination() throws Exception {
		var first = get("/api/reservations?reservationStatus=CONFIRMED&from=2030-01-01&to=2030-01-31&page=0&size=1"
				+ "&userId=" + bob.getId() + "&externalPatientId=" + patientId(bob), aliceToken);
		assertThat(first.statusCode()).isEqualTo(200);
		assertThat(first.headers().firstValue("Cache-Control")).contains("no-store");
		var firstBody = json.readTree(first.body());
		assertThat(firstBody.get("total").asLong()).isEqualTo(2);
		assertThat(firstBody.get("page").asInt()).isZero();
		assertThat(firstBody.get("size").asInt()).isEqualTo(1);
		assertThat(firstBody.get("items").size()).isEqualTo(1);
		assertThat(firstBody.get("items").get(0).get("id").asLong()).isEqualTo(aliceSecondProcessId);
		assertThat(firstBody.get("items").get(0).get("processStatus").asString()).isEqualTo("CONFIRMED");
		assertThat(firstBody.get("items").get(0).get("reservationStatus").asString()).isEqualTo("CONFIRMED");
		assertThat(first.body()).doesNotContain("alice-process", "reservationProcessId", "holdId", patientId(alice),
				"patientPhone", "+549", "ownerUserId", "technicalJwt");

		var second = get("/api/reservations?reservationStatus=CONFIRMED&from=2030-01-01&to=2030-01-31&page=1&size=1", aliceToken);
		assertThat(second.statusCode()).isEqualTo(200);
		assertThat(json.readTree(second.body()).get("total").asLong()).isEqualTo(2);
		assertThat(json.readTree(second.body()).get("items").get(0).get("id").asLong()).isEqualTo(aliceFirstProcessId);

		var bobPage = get("/api/reservations", bobToken);
		assertThat(bobPage.statusCode()).isEqualTo(200);
		assertThat(json.readTree(bobPage.body()).get("total").asLong()).isEqualTo(1);
		assertThat(json.readTree(bobPage.body()).get("items").get(0).get("id").asLong()).isEqualTo(bobProcessId);
		assertThat(bobPage.body()).doesNotContain("alice-process", "alice@example.test");
		assertThat(listRequests).hasValue(3);
		assertThat(catedraAuthorization.get()).isEqualTo("Bearer " + TECHNICAL_JWT);
	}

	@Test
	void cancellationUsesLocalIdRequiresOwnershipAndIsIdempotentAfterVerifiedSuccess() throws Exception {
		assertThat(get("/api/reservations", aliceToken).statusCode()).isEqualTo(200);
		var canceled = post("/api/reservations/" + aliceFirstProcessId + "/cancel", aliceToken,
				"{\"reason\":\"User requested cancellation\"}");
		assertThat(canceled.statusCode()).isEqualTo(200);
		assertThat(json.readTree(canceled.body()).get("id").asLong()).isEqualTo(aliceFirstProcessId);
		assertThat(json.readTree(canceled.body()).get("status").asString()).isEqualTo("CANCELLED");
		assertThat(canceled.body()).doesNotContain("alice-process", "8251", "User requested cancellation", "holdId");
		assertThat(processes.findById(aliceFirstProcessId).orElseThrow().getStatus()).isEqualTo(ReservationProcessStatus.CANCELLED);
		assertThat(processes.findById(aliceFirstProcessId).orElseThrow().getCancellationReason()).isEqualTo("User requested cancellation");
		assertThat(cancellationBody.get()).contains("User requested cancellation");
		assertThat(catedraAuthorization.get()).isEqualTo("Bearer " + TECHNICAL_JWT);

		var repeated = post("/api/reservations/" + aliceFirstProcessId + "/cancel", aliceToken, "{}");
		assertThat(repeated.statusCode()).isEqualTo(200);
		assertThat(repeated.body()).isEqualTo(canceled.body());
		assertThat(cancellationRequests).hasValue(1);

		var foreign = post("/api/reservations/" + aliceFirstProcessId + "/cancel", bobToken, "{}");
		var absent = post("/api/reservations/9223372036854775807/cancel", bobToken, "{}");
		assertProblem(foreign, 404, "RESERVATION_PROCESS_NOT_FOUND");
		assertProblem(absent, 404, "RESERVATION_PROCESS_NOT_FOUND");
		assertThat(json.readTree(foreign.body()).get("detail").asString())
				.isEqualTo(json.readTree(absent.body()).get("detail").asString());
		assertThat(cancellationRequests).hasValue(1);
	}

	@Test
	void invalidIdentityAndFiltersDoNotReachTheCatedraOrRevealPersonalData() throws Exception {
		var unauthorized = get("/api/reservations?userId=" + bob.getId(), null);
		assertProblem(unauthorized, 401, "UNAUTHORIZED");
		var invalid = get("/api/reservations?from=2030-02-01&to=2030-01-01", aliceToken);
		assertProblem(invalid, 400, "INVALID_REQUEST");
		assertThat(listRequests).hasValue(0);
		assertThat(cancellationRequests).hasValue(0);
	}

	@Test
	void anAmbiguousRemoteCancellationNeverProducesACancelledSuccess() throws Exception {
		assertThat(get("/api/reservations", aliceToken).statusCode()).isEqualTo(200);
		catedraCancellationStatus.set(503);
		var response = post("/api/reservations/" + aliceFirstProcessId + "/cancel", aliceToken, "{}");
		assertProblem(response, 503, "CANCELLATION_OUTCOME_UNKNOWN");
		assertThat(response.body()).doesNotContain("CANCELLED", TECHNICAL_JWT, "alice-process");
		assertThat(processes.findById(aliceFirstProcessId).orElseThrow().getStatus())
				.isEqualTo(ReservationProcessStatus.CANCELLATION_PENDING);
		assertThat(cancellationRequests).hasValue(1);
	}

	private UserEntity createUser(String login, String email) {
		var user = new UserEntity(login, ARGON_HASH, null, null, email, null, "es", "test");
		user.activate("test");
		return users.saveAndFlush(user);
	}

	private long createProcess(UserEntity user, String externalProcessId, String holdId, LocalDate date, LocalTime time) {
		var process = new ReservationProcessEntity(users.getReferenceById(user.getId()), 201, date, time,
				ExternalPatientId.fromUserId(user.getId()), "test");
		process.markHeld(holdId, externalProcessId, Instant.parse("2030-02-01T00:00:00Z"), "test");
		return processes.saveAndFlush(process).getId();
	}

	private String token(UserEntity user, String login) {
		var properties = new FinalUserJwtProperties();
		properties.setBase64Secret(FinalUserJwtTestSupport.signingSecret());
		var issued = new NimbusFinalUserTokenIssuer(properties, Clock.systemUTC()).issue(
				new FinalUserPrincipal(user.getId(), login, ARGON_HASH, true,
						List.of(new SimpleGrantedAuthority("ROLE_USER"))), false).value();
		issuedTokens.add(issued);
		return issued;
	}

	private HttpResponse<String> get(String path, String bearer) throws Exception {
		var request = HttpRequest.newBuilder(URI.create(baseUrl + path)).timeout(Duration.ofSeconds(15)).GET();
		if (bearer != null) { request.header("Authorization", "Bearer " + bearer); }
		return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
	}

	private HttpResponse<String> post(String path, String bearer, String body) throws Exception {
		var request = HttpRequest.newBuilder(URI.create(baseUrl + path)).timeout(Duration.ofSeconds(15))
				.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body));
		if (bearer != null) { request.header("Authorization", "Bearer " + bearer); }
		return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
	}

	private void catedraRequest(HttpExchange exchange) throws IOException {
		try (exchange) {
			catedraAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
			var path = exchange.getRequestURI().getPath();
			if ("GET".equals(exchange.getRequestMethod()) && "/api/appointments".equals(path)) {
				listRequests.incrementAndGet();
				if (catedraListStatus.get() != 200) {
					respond(exchange, catedraListStatus.get(), "application/problem+json", "{\"code\":\"SERVICE_UNAVAILABLE\"}");
					return;
				}
				var query = query(exchange.getRequestURI());
				var matches = remoteRows.stream().filter(row -> matches(row, query))
						.sorted(Comparator.comparing(Row::date).thenComparing(Row::startTime).reversed()).toList();
				int page = Integer.parseInt(query.getOrDefault("page", "0"));
				int size = Integer.parseInt(query.getOrDefault("size", "20"));
				int start = Math.min(page * size, matches.size());
				int end = Math.min(start + size, matches.size());
				exchange.getResponseHeaders().set("X-Total-Count", Integer.toString(matches.size()));
				respond(exchange, 200, "application/json", matches.subList(start, end).stream()
						.map(this::rowJson).collect(Collectors.joining(",", "[", "]")));
			} else if ("POST".equals(exchange.getRequestMethod()) && path.startsWith("/api/appointments/")
					&& path.endsWith("/cancel")) {
				cancellationRequests.incrementAndGet();
				cancellationBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
				if (catedraCancellationStatus.get() != 200) {
					respond(exchange, catedraCancellationStatus.get(), "application/problem+json", "{\"code\":\"REMOTE_FAILURE\"}");
					return;
				}
				String externalId = path.substring("/api/appointments/".length(), path.length() - "/cancel".length());
				var row = remoteRows.stream().filter(candidate -> candidate.processId().equals(externalId)).findFirst().orElse(null);
				if (row == null) { respond(exchange, 404, "application/problem+json", "{}"); return; }
				remoteRows.remove(row);
				remoteRows.add(row.cancelled());
				respond(exchange, 200, "application/json", "{\"reservationId\":" + row.reservationId()
						+ ",\"reservationProcessId\":\"" + row.processId() + "\",\"status\":\"CANCELLED\",\"cancelledAt\":\""
						+ Instant.now() + "\"}");
			} else {
				respond(exchange, 404, "application/problem+json", "{}");
			}
		}
	}

	private static boolean matches(Row row, Map<String, String> query) {
		return (query.get("externalPatientId") == null || query.get("externalPatientId").equals(row.patientId()))
				&& (query.get("status") == null || query.get("status").equals(row.status().name()))
				&& (query.get("from") == null || !row.date().isBefore(LocalDate.parse(query.get("from"))))
				&& (query.get("to") == null || !row.date().isAfter(LocalDate.parse(query.get("to"))));
	}

	private String rowJson(Row row) {
		return "{\"reservationId\":" + row.reservationId() + ",\"reservationProcessId\":\"" + row.processId()
				+ "\",\"externalPatientId\":\"" + row.patientId() + "\",\"appointmentDate\":\"" + row.date()
				+ "\",\"startTime\":\"" + row.startTime() + "\",\"endTime\":\"" + row.endTime()
				+ "\",\"professionalId\":" + row.professionalId() + ",\"professionalFirstName\":\"Martin\","
				+ "\"professionalLastName\":\"Rivas\",\"patientFirstName\":\"Private\",\"patientLastName\":\"Patient\","
				+ "\"patientPhone\":\"+5492615551234\",\"status\":\"" + row.status()
				+ "\",\"createdAt\":\"" + CREATED + "\",\"confirmedAt\":\"" + CREATED
				+ "\",\"cancelledAt\":" + (row.status() == TechnicalStatus.CANCELLED ? "\"" + Instant.now() + "\"" : "null")
				+ ",\"cancellationReason\":null}";
	}

	private static Map<String, String> query(URI uri) {
		var result = new HashMap<String, String>();
		if (uri.getRawQuery() == null) { return result; }
		for (String pair : uri.getRawQuery().split("&")) {
			var parts = pair.split("=", 2);
			result.put(URLDecoder.decode(parts[0], StandardCharsets.UTF_8),
					parts.length == 1 ? "" : URLDecoder.decode(parts[1], StandardCharsets.UTF_8));
		}
		return result;
	}

	private static String patientId(UserEntity user) { return "user-" + user.getId(); }

	private void assertProblem(HttpResponse<String> response, int status, String code) throws Exception {
		assertThat(response.statusCode()).isEqualTo(status);
		assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
		assertThat(response.headers().firstValue("Content-Type").orElseThrow()).startsWith("application/problem+json");
		assertThat(json.readTree(response.body()).get("code").asString()).isEqualTo(code);
	}

	private Connection connection() throws SQLException { return DriverManager.getConnection(url, username, password); }

	private static String requiredEnvironment(String name) {
		String value = System.getenv(name);
		if (value == null || value.isBlank()) { throw new IllegalStateException("Set " + name + " for postgres-integration"); }
		return value;
	}

	private record Row(long reservationId, String processId, String patientId, LocalDate date, LocalTime startTime,
			LocalTime endTime, long professionalId, TechnicalStatus status) {
		private Row cancelled() { return new Row(reservationId, processId, patientId, date, startTime, endTime, professionalId, TechnicalStatus.CANCELLED); }
	}
	private enum TechnicalStatus { PHONE_PENDING, CONFIRMED, CANCELLED, FAILED }

	private static void respond(HttpExchange exchange, int status, String type, String body) throws IOException {
		byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().set("Content-Type", type);
		exchange.sendResponseHeaders(status, bytes.length);
		exchange.getResponseBody().write(bytes);
	}
}
