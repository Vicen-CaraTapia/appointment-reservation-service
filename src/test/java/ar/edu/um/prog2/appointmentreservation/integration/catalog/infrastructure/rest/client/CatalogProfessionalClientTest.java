package ar.edu.um.prog2.appointmentreservation.integration.catalog.infrastructure.rest.client;

import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.SequenceInputStream;
import java.io.StringWriter;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.SocketTimeoutException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import ar.edu.um.prog2.appointmentreservation.integration.catalog.infrastructure.config.CatalogIntegrationConfiguration;
import ar.edu.um.prog2.appointmentreservation.integration.catalog.infrastructure.config.CatalogIntegrationProperties;
import ar.edu.um.prog2.appointmentreservation.integration.catalog.infrastructure.rest.exception.CatalogClientException;
import ar.edu.um.prog2.appointmentreservation.integration.catalog.infrastructure.rest.exception.CatalogClientException.Reason;
import ar.edu.um.prog2.appointmentreservation.support.CatalogJwtTestSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

@ExtendWith(OutputCaptureExtension.class)
class CatalogProfessionalClientTest {

	private static final String PRIVATE_DETAIL = "test-only-remote-private-diagnostic";
	private static final String RESPONSE = """
			{"id":201,"firstName":"Ana","lastName":"Torres","enabled":true,
			 "category":{"id":101,"name":"Cardiologia","enabled":true},
			 "weeklySchedules":[{"id":301,"dayOfWeek":"MONDAY","startTime":"09:00:00",
			 "endTime":"12:00:00.123456789","slotDurationMinutes":30,"enabled":true}]}
			""";
	private static final List<String> REQUIRED_FIELDS = List.of("id", "firstName", "lastName", "enabled", "category", "weeklySchedules",
			"category.id", "category.name", "category.enabled", "weeklySchedules.0.id", "weeklySchedules.0.dayOfWeek",
			"weeklySchedules.0.startTime", "weeklySchedules.0.endTime", "weeklySchedules.0.slotDurationMinutes", "weeklySchedules.0.enabled");
	private static final JsonMapper JSON = JsonMapper.builder().build();
	private final List<HttpServer> servers = new ArrayList<>();
	private final List<String> authorizationHeaders = new CopyOnWriteArrayList<>();

	@TempDir
	Path directory;
	private Path keyFile;

	@BeforeEach
	void createKey() throws IOException {
		keyFile = CatalogJwtTestSupport.writePrivateKey(directory, CatalogJwtTestSupport.signingKeyPair().getPrivate());
	}

	@AfterEach
	void cleanupAndVerifyLogs(CapturedOutput output) {
		servers.forEach(server -> server.stop(0));
		assertThat(output.getAll()).doesNotContain(PRIVATE_DETAIL, keyFile.toString());
		authorizationHeaders.forEach(header -> assertThat(output.getAll()).doesNotContain(header, header.substring("Bearer ".length())));
	}

	@Test
	void requestsTheContractEndpointWithAGenuinelySignedInternalToken() throws IOException {
		var path = new AtomicReference<String>();
		var method = new AtomicReference<String>();
		var accept = new AtomicReference<String>();
		var server = server(exchange -> {
			path.set(exchange.getRequestURI().toString());
			method.set(exchange.getRequestMethod());
			accept.set(exchange.getRequestHeaders().getFirst("Accept"));
			respond(exchange, 200, "application/json", RESPONSE);
		});

		var professional = client(url(server), Duration.ofSeconds(5)).getProfessional(201);

		assertThat(path.get()).isEqualTo("/internal/api/professionals/201");
		assertThat(method.get()).isEqualTo("GET");
		assertThat(accept.get()).isEqualTo("application/json");
		assertThat(professional.id()).isEqualTo(201);
		assertThat(professional.firstName()).isEqualTo("Ana");
		assertThat(professional.lastName()).isEqualTo("Torres");
		assertThat(professional.category().name()).isEqualTo("Cardiologia");
		assertThat(professional.weeklySchedules().getFirst().dayOfWeek().name()).isEqualTo("MONDAY");
		assertThat(professional.weeklySchedules().getFirst().startTime().toString()).isEqualTo("09:00");
		assertThat(professional.weeklySchedules().getFirst().endTime().getNano()).isEqualTo(123456789);
		assertThat(professional.weeklySchedules().getFirst().slotDurationMinutes()).isEqualTo(30);
		assertThatThrownBy(() -> professional.weeklySchedules().clear()).isInstanceOf(UnsupportedOperationException.class);
		var decoder = NimbusJwtDecoder.withPublicKey((RSAPublicKey) CatalogJwtTestSupport.signingKeyPair().getPublic())
				.signatureAlgorithm(SignatureAlgorithm.RS256).build();
		var token = decoder.decode(authorizationHeaders.getFirst().substring("Bearer ".length()));
		assertThat(token.getHeaders()).containsEntry("alg", "RS256");
		assertThat(token.getSubject()).isEqualTo("appointment-reservation-service");
		assertThat(token.getClaimAsString("iss")).isEqualTo("appointment-reservation-service");
		assertThat(token.getAudience()).containsExactly("catalog-sync-service");
		assertThat(token.getClaimAsString("scope")).isEqualTo("catalog:read");
		assertThat(Duration.between(token.getIssuedAt(), token.getExpiresAt())).isEqualTo(Duration.ofSeconds(75));
	}

	@Test
	void honorsAnExternallyConfiguredBasePath() throws IOException {
		var path = new AtomicReference<String>();
		var server = server(exchange -> {
			path.set(exchange.getRequestURI().getPath());
			respond(exchange, 200, "application/json", RESPONSE);
		});
		client(url(server) + "/gateway", Duration.ofSeconds(5)).getProfessional(201);
		assertThat(path.get()).isEqualTo("/gateway/internal/api/professionals/201");
	}

	@ParameterizedTest
	@ValueSource(longs = {Long.MIN_VALUE, -1, 0, Long.MAX_VALUE})
	void preservesSignedInt64IdentifiersWithoutAddingPositivityRules(long id) throws IOException {
		var json = response();
		json.put("id", id);
		((ObjectNode) json.get("category")).put("id", Long.MAX_VALUE);
		((ObjectNode) json.get("weeklySchedules").get(0)).put("id", Long.MAX_VALUE - 1);
		var professional = respondWith(JSON.writeValueAsString(json)).getProfessional(id);
		assertThat(professional.id()).isEqualTo(id);
		assertThat(professional.category().id()).isEqualTo(Long.MAX_VALUE);
		assertThat(professional.weeklySchedules().getFirst().id()).isEqualTo(Long.MAX_VALUE - 1);
	}

	@Test
	void preservesDisabledStatesAndEmptySchedules() throws IOException {
		var json = response();
		json.put("enabled", false);
		((ObjectNode) json.get("category")).put("enabled", false);
		((ObjectNode) json.get("weeklySchedules").get(0)).put("enabled", false);
		var professional = respondWith(JSON.writeValueAsString(json)).getProfessional(201);
		assertThat(professional.enabled()).isFalse();
		assertThat(professional.category().enabled()).isFalse();
		assertThat(professional.weeklySchedules().getFirst().enabled()).isFalse();
		json.putArray("weeklySchedules");
		assertThat(respondWith(JSON.writeValueAsString(json)).getProfessional(201).weeklySchedules()).isEmpty();
	}

	@Test
	void toleratesAdditionalFieldsWithoutChangingTheRequiredContract() throws IOException {
		var json = response();
		json.put("futureField", PRIVATE_DETAIL);
		((ObjectNode) json.get("category")).put("futureField", true);
		((ObjectNode) json.get("weeklySchedules").get(0)).put("futureField", 1);
		assertThat(respondWith(JSON.writeValueAsString(json)).getProfessional(201).id()).isEqualTo(201);
	}

	@ParameterizedTest
	@ValueSource(ints = {400, 401, 403, 404, 409, 500, 503})
	void preservesHttpStatusAndNeverBranchesOnOrExposesProblemDetail(int status) throws IOException {
		var count = new AtomicInteger();
		var server = server(exchange -> {
			count.incrementAndGet();
			respond(exchange, status, "application/problem+json", "{\"status\":200,\"detail\":\"" + PRIVATE_DETAIL + "\"}");
		});
		assertFailure(client(url(server), Duration.ofSeconds(5)), Reason.HTTP, status);
		assertThat(count.get()).isEqualTo(1);
	}

	@ParameterizedTest
	@ValueSource(strings = {"", "{bad-json", "[]", "null", "{}", "{} {}"})
	void rejectsMissingOrMalformedResponses(String body) throws IOException {
		assertFailure(respondWith(body), Reason.INVALID_RESPONSE, null);
	}

	@ParameterizedTest
	@MethodSource("missingAndNullFields")
	void rejectsEveryMissingOrNullRequiredField(String path, boolean remove) throws IOException {
		var json = response();
		var parent = parent(json, path);
		String field = path.substring(path.lastIndexOf('.') + 1);
		if (remove) {
			parent.remove(field);
		} else {
			parent.putNull(field);
		}
		assertFailure(respondWith(JSON.writeValueAsString(json)), Reason.INVALID_RESPONSE, null);
	}

	@ParameterizedTest
	@MethodSource("invalidFieldTypes")
	void rejectsIncompatibleTypesWithoutCoercingOrTruncatingValues(String path, String value) throws IOException {
		var json = response();
		parent(json, path).set(path.substring(path.lastIndexOf('.') + 1), JSON.readTree(value));
		assertFailure(respondWith(JSON.writeValueAsString(json)), Reason.INVALID_RESPONSE, null);
	}

	@ParameterizedTest
	@ValueSource(strings = {"text/plain", "application/problem+json", "invalid-media-type"})
	void rejectsAnUnexpectedSuccessContentType(String type) throws IOException {
		var server = server(exchange -> respond(exchange, 200, type, RESPONSE));
		assertFailure(client(url(server), Duration.ofSeconds(5)), Reason.INVALID_RESPONSE, null);
	}

	@Test
	void rejectsAMissingContentType() throws IOException {
		var server = server(exchange -> respond(exchange, 200, null, RESPONSE));
		assertFailure(client(url(server), Duration.ofSeconds(5)), Reason.INVALID_RESPONSE, null);
	}

	@ParameterizedTest
	@ValueSource(ints = {201, 204, 206})
	void rejectsSuccessStatusesOutsideTheDocumentedContract(int status) throws IOException {
		var server = server(exchange -> respond(exchange, status, "application/json", RESPONSE));
		assertFailure(client(url(server), Duration.ofSeconds(5)), Reason.INVALID_RESPONSE, null);
	}

	@Test
	void rejectsAResponseForADifferentProfessional() throws IOException {
		assertThatThrownBy(() -> respondWith(RESPONSE).getProfessional(202))
				.isInstanceOfSatisfying(CatalogClientException.class, error -> assertThat(error.reason()).isEqualTo(Reason.INVALID_RESPONSE));
	}

	@Test
	void malformedPayloadCannotExposeTheInternalTokenThroughAConversionFailure() throws IOException {
		var server = server(exchange -> {
			var json = response();
			json.putObject("firstName").put("private", exchange.getRequestHeaders().getFirst("Authorization"));
			respond(exchange, 200, "application/json", JSON.writeValueAsString(json));
		});
		assertFailure(client(url(server), Duration.ofSeconds(5)), Reason.INVALID_RESPONSE, null);
	}

	@Test
	void doesNotFollowRedirectsOrForwardTheTokenToTheDestination() throws IOException {
		var count = new AtomicInteger();
		var destination = server(exchange -> {
			count.incrementAndGet();
			respond(exchange, 200, "application/json", RESPONSE);
		});
		var redirect = server(exchange -> {
			exchange.getResponseHeaders().set("Location", url(destination) + "/other");
			respond(exchange, 302, "text/plain", "");
		});
		assertFailure(client(url(redirect), Duration.ofSeconds(5)), Reason.HTTP, 302);
		assertThat(count.get()).isZero();
	}

	@Test
	void connectionFailureIsNotAnUnknownProfessional() throws IOException {
		int port;
		try (var socket = new ServerSocket(0)) {
			port = socket.getLocalPort();
		}
		assertFailure(client("http://127.0.0.1:" + port, Duration.ofSeconds(5)), Reason.TRANSPORT, null);
	}

	@Test
	void appliesTheConfiguredReadTimeoutWithoutUsingAnExternalServer() throws IOException {
		var release = new CountDownLatch(1);
		var server = server(exchange -> {
			try {
				release.await(5, TimeUnit.SECONDS);
			} catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
			}
		});
		try {
			assertFailure(client(url(server), Duration.ofMillis(100)), Reason.TIMEOUT, null);
		} finally {
			release.countDown();
		}
	}

	@ParameterizedTest(name = "transport failure {index}")
	@MethodSource("transportFailures")
	void classifiesTransportFailuresByCauseTypeWithoutReadingTheirMessages(IOException failure, Reason expected) {
		var http = RestClient.builder().baseUrl("https://catalog.example.test")
				.requestFactory((uri, method) -> { throw failure; }).build();
		assertFailure(new CatalogProfessionalClient(http), expected, null);
	}

	@ParameterizedTest(name = "response body failure {index}")
	@MethodSource("bodyFailures")
	void recognizesTimeoutAndIoFailuresWrappedDuringJsonDecoding(IOException failure, Reason expected) {
		assertFailure(clientWithBodyFailure(failure, HttpStatus.OK), expected, null);
	}

	@ParameterizedTest
	@ValueSource(ints = {401, 403, 404, 500, 503})
	void anObservedHttpErrorTakesPrecedenceOverAnUnreadableErrorBody(int status) {
		assertFailure(clientWithBodyFailure(new SocketTimeoutException(PRIVATE_DETAIL), HttpStatus.valueOf(status)), Reason.HTTP, status);
	}

	@Test
	void malformedJsonContainingTimeoutWordsIsStillAContractError() throws IOException {
		assertFailure(respondWith("{\"message\":\"timed out\"}"), Reason.INVALID_RESPONSE, null);
	}

	@Test
	void boundsAStalledBodyAndKeepsUnmarkedIoAsTransportFailure() throws IOException {
		var release = new CountDownLatch(1);
		var headersSent = new CountDownLatch(1);
		var server = server(exchange -> {
			exchange.getResponseHeaders().set("Content-Type", "application/json");
			exchange.sendResponseHeaders(200, RESPONSE.getBytes(StandardCharsets.UTF_8).length);
			exchange.getResponseBody().write("{\"id\":".getBytes(StandardCharsets.UTF_8));
			exchange.getResponseBody().flush();
			headersSent.countDown();
			try {
				release.await(5, TimeUnit.SECONDS);
			} catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
			}
		});
		var client = client(url(server), Duration.ofMillis(500));
		try {
			assertTimeoutPreemptively(Duration.ofSeconds(3), () -> assertFailure(client, Reason.TRANSPORT, null));
			assertThat(headersSent.getCount()).isZero();
		} finally {
			release.countDown();
		}
	}

	private CatalogProfessionalClient clientWithBodyFailure(IOException failure, HttpStatus status) {
		var http = RestClient.builder().baseUrl("https://catalog.example.test").requestFactory((uri, method) -> {
			var request = new MockClientHttpRequest(method, uri);
			var prefix = new ByteArrayInputStream("{\"id\":".getBytes(StandardCharsets.UTF_8));
			var response = new MockClientHttpResponse(new SequenceInputStream(prefix, new InputStream() {
				@Override
				public int read() throws IOException {
					throw failure;
				}
			}), status);
			response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
			request.setResponse(response);
			return request;
		}).build();
		return new CatalogProfessionalClient(http);
	}

	private CatalogProfessionalClient respondWith(String body) throws IOException {
		return client(url(server(exchange -> respond(exchange, 200, "application/json", body))), Duration.ofSeconds(5));
	}

	private CatalogProfessionalClient client(String baseUrl, Duration readTimeout) {
		var properties = new CatalogIntegrationProperties(baseUrl, Duration.ofSeconds(2), readTimeout,
				keyFile.toUri().toString(), "appointment-reservation-service", "catalog-sync-service", Duration.ofSeconds(75));
		var configuration = new CatalogIntegrationConfiguration();
		return configuration.catalogProfessionalClient(properties, configuration.catalogInternalTokenIssuer(properties));
	}

	private HttpServer server(HttpHandler handler) throws IOException {
		var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/", exchange -> {
			var authorization = exchange.getRequestHeaders().getFirst("Authorization");
			if (authorization != null) {
				authorizationHeaders.add(authorization);
			}
			try {
				handler.handle(exchange);
			} finally {
				exchange.close();
			}
		});
		server.start();
		servers.add(server);
		return server;
	}

	private static String url(HttpServer server) {
		return "http://127.0.0.1:" + server.getAddress().getPort();
	}

	private static void respond(HttpExchange exchange, int status, String type, String body) throws IOException {
		if (type != null) {
			exchange.getResponseHeaders().set("Content-Type", type);
		}
		if (status == 204 || body.isEmpty()) {
			exchange.sendResponseHeaders(status, -1);
			return;
		}
		var bytes = body.getBytes(StandardCharsets.UTF_8);
		exchange.sendResponseHeaders(status, bytes.length);
		exchange.getResponseBody().write(bytes);
	}

	private void assertFailure(CatalogProfessionalClient client, Reason reason, Integer status) {
		assertThatThrownBy(() -> client.getProfessional(201)).isInstanceOfSatisfying(CatalogClientException.class, error -> {
			assertThat(error.reason()).isEqualTo(reason);
			if (status == null) {
				assertThat(error.status()).isEmpty();
			} else {
				assertThat(error.status()).hasValue(status);
			}
			assertThat(error).hasNoCause();
			var writer = new StringWriter();
			error.printStackTrace(new PrintWriter(writer));
			assertThat(writer.toString()).doesNotContain(PRIVATE_DETAIL);
			authorizationHeaders.forEach(header -> assertThat(writer.toString()).doesNotContain(header.substring("Bearer ".length())));
		});
	}

	private static ObjectNode response() {
		return (ObjectNode) JSON.readTree(RESPONSE);
	}

	private static ObjectNode parent(JsonNode root, String path) {
		var parts = path.split("\\.");
		for (int i = 0; i < parts.length - 1; i++) {
			root = root.isArray() ? root.get(Integer.parseInt(parts[i])) : root.get(parts[i]);
		}
		return (ObjectNode) root;
	}

	static Stream<Arguments> missingAndNullFields() {
		return REQUIRED_FIELDS.stream().flatMap(path -> Stream.of(Arguments.of(path, true), Arguments.of(path, false)));
	}

	static Stream<Arguments> transportFailures() {
		return Stream.of(
				Arguments.of(new HttpTimeoutException(PRIVATE_DETAIL), Reason.TIMEOUT),
				Arguments.of(new HttpConnectTimeoutException(PRIVATE_DETAIL), Reason.TIMEOUT),
				Arguments.of(new SocketTimeoutException(PRIVATE_DETAIL), Reason.TIMEOUT),
				Arguments.of(new IOException(PRIVATE_DETAIL, new TimeoutException(PRIVATE_DETAIL)), Reason.TIMEOUT),
				Arguments.of(new ConnectException(PRIVATE_DETAIL), Reason.TRANSPORT),
				Arguments.of(new IOException("timed out " + PRIVATE_DETAIL), Reason.TRANSPORT));
	}

	static Stream<Arguments> bodyFailures() {
		return Stream.of(
				Arguments.of(new SocketTimeoutException(PRIVATE_DETAIL), Reason.TIMEOUT),
				Arguments.of(new IOException(PRIVATE_DETAIL, new HttpTimeoutException(PRIVATE_DETAIL)), Reason.TIMEOUT),
				Arguments.of(new EOFException(PRIVATE_DETAIL), Reason.TRANSPORT),
				Arguments.of(new IOException(PRIVATE_DETAIL), Reason.TRANSPORT));
	}

	static Stream<Arguments> invalidFieldTypes() {
		return Stream.of(
				Arguments.of("id", "201.5"), Arguments.of("id", "\"201\""), Arguments.of("id", "9223372036854775808"),
				Arguments.of("enabled", "\"true\""), Arguments.of("enabled", "1"), Arguments.of("firstName", "42"),
				Arguments.of("category", "[]"), Arguments.of("weeklySchedules", "{}"), Arguments.of("weeklySchedules", "[null]"),
				Arguments.of("weeklySchedules.0.dayOfWeek", "1"), Arguments.of("weeklySchedules.0.dayOfWeek", "\"MON\""),
				Arguments.of("weeklySchedules.0.startTime", "[9,0,0]"), Arguments.of("weeklySchedules.0.startTime", "\"09:00:00Z\""),
				Arguments.of("weeklySchedules.0.startTime", "\"2030-01-01T09:00:00\""),
				Arguments.of("weeklySchedules.0.startTime", "\"09:00:00+01:00\""),
				Arguments.of("weeklySchedules.0.endTime", "\"25:00:00\""), Arguments.of("weeklySchedules.0.slotDurationMinutes", "30.5"),
				Arguments.of("weeklySchedules.0.slotDurationMinutes", "\"30\""), Arguments.of("weeklySchedules.0.slotDurationMinutes", "2147483648"));
	}
}
