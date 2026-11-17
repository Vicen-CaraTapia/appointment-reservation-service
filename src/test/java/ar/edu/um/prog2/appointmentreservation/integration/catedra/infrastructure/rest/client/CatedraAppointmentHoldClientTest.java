package ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.rest.client;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import tools.jackson.databind.json.JsonMapper;

import ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.config.CatedraIntegrationProperties;
import ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.rest.config.CatedraRestClientConfiguration;
import ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.rest.dto.CatedraAppointmentHoldRequest;
import ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.rest.exception.CatedraHoldException;
import ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.rest.exception.CatedraHttpException;
import ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.rest.exception.CatedraProtocolException;
import ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.rest.exception.CatedraTimeoutException;
import ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.rest.exception.CatedraTransportException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ExtendWith(OutputCaptureExtension.class)
class CatedraAppointmentHoldClientTest {

	private static final String TEST_JWT = "test-only-hold-technical-token";
	private static final String PRIVATE_DETAIL = "private-remote-diagnostic";
	private static final CatedraAppointmentHoldRequest REQUEST = new CatedraAppointmentHoldRequest(
			Long.MAX_VALUE, LocalDate.of(2026, 11, 17), LocalTime.of(11, 30), "user-42");
	private static final String RESPONSE = """
			{"holdId":"hold-opaque","reservationProcessId":"process-opaque","status":"HELD",
			 "expiresAt":"2026-11-17T14:35:00.123456Z"}
			""";
	private final List<HttpServer> servers = new ArrayList<>();
	private final AtomicInteger requests = new AtomicInteger();

	@AfterEach
	void cleanupAndCheckLogs(CapturedOutput output) {
		servers.forEach(server -> server.stop(0));
		assertThat(output.getAll()).doesNotContain(TEST_JWT, PRIVATE_DETAIL);
	}

	@Test
	void postsTheExactContractWithTechnicalAuthenticationAndPreservesTheRemoteExpiry() throws IOException {
		var method = new AtomicReference<String>();
		var path = new AtomicReference<String>();
		var authorization = new AtomicReference<String>();
		var accept = new AtomicReference<String>();
		var contentType = new AtomicReference<String>();
		var body = new AtomicReference<String>();
		var client = start(exchange -> {
			method.set(exchange.getRequestMethod());
			path.set(exchange.getRequestURI().toString());
			authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
			accept.set(exchange.getRequestHeaders().getFirst("Accept"));
			contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
			body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
			respond(exchange, 201, "application/json", RESPONSE);
		});
		var result = client.createHold(REQUEST);
		assertThat(method.get()).isEqualTo("POST");
		assertThat(path.get()).isEqualTo("/api/appointment-holds");
		assertThat(authorization.get()).isEqualTo("Bearer " + TEST_JWT);
		assertThat(accept.get()).isEqualTo("application/json");
		assertThat(contentType.get()).startsWith("application/json");
		assertThat(new JsonMapper().readTree(body.get())).isEqualTo(new JsonMapper().readTree("""
				{"professionalId":9223372036854775807,"date":"2026-11-17",
				 "startTime":"11:30:00","externalPatientId":"user-42"}
				"""));
		assertThat(result.holdId()).isEqualTo("hold-opaque");
		assertThat(result.reservationProcessId()).isEqualTo("process-opaque");
		assertThat(result.status()).isEqualTo("HELD");
		assertThat(result.expiresAt()).isEqualTo(Instant.parse("2026-11-17T14:35:00.123456Z"));
		assertThat(requests.get()).isEqualTo(1);
	}

	@Test
	void acceptsCompatibleAdditionalFieldsAndOffsetExpiryWithoutChangingOpaqueIdentifiers() throws IOException {
		String id = "opaque-" + "x".repeat(300);
		var response = RESPONSE.replace("hold-opaque", id)
				.replace("2026-11-17T14:35:00.123456Z", "2026-11-17T11:35:00.123456-03:00")
				.replace("\"status\":\"HELD\"", "\"futureField\":{\"v\":2},\"status\":\"HELD\"");
		var result = start(exchange -> respond(exchange, 201, "application/json", response)).createHold(REQUEST);
		assertThat(result.holdId()).isEqualTo(id);
		assertThat(result.expiresAt()).isEqualTo(Instant.parse("2026-11-17T14:35:00.123456Z"));
	}

	@ParameterizedTest
	@ValueSource(ints = {200, 202, 204, 207})
	void rejectsUnexpectedSuccessStatusesWithoutRetrying(int status) throws IOException {
		var client = start(exchange -> respond(exchange, status, "application/json", status == 204 ? "" : RESPONSE));
		assertThatThrownBy(() -> client.createHold(REQUEST)).isInstanceOfSatisfying(CatedraProtocolException.class,
				CatedraAppointmentHoldClientTest::assertSafe);
		assertThat(requests.get()).isEqualTo(1);
	}

	@ParameterizedTest
	@NullSource
	@ValueSource(strings = {"text/plain", "application/problem+json"})
	void rejectsMissingOrIncorrectSuccessContentType(String mediaType) throws IOException {
		var client = start(exchange -> respond(exchange, 201, mediaType, RESPONSE));
		assertThatThrownBy(() -> client.createHold(REQUEST)).isInstanceOf(CatedraProtocolException.class);
	}

	@ParameterizedTest
	@ValueSource(strings = {"", "null", "[]", "42", "{broken"})
	void rejectsEmptyMalformedOrNonObjectSuccessBodies(String body) throws IOException {
		var client = start(exchange -> respond(exchange, 201, "application/json", body));
		assertThatThrownBy(() -> client.createHold(REQUEST)).isInstanceOfSatisfying(CatedraProtocolException.class,
				CatedraAppointmentHoldClientTest::assertSafe);
	}

	@ParameterizedTest
	@ValueSource(strings = {"holdId", "reservationProcessId", "status", "expiresAt"})
	void rejectsMissingNullOrNonTextualRequiredFields(String field) throws IOException {
		for (String value : List.of("missing", "null", "42", "true", "[]", "{}", "\"\"", "\" \"")) {
			var tree = new JsonMapper().readTree(RESPONSE).deepCopy();
			var object = (tools.jackson.databind.node.ObjectNode) tree;
			if (value.equals("missing")) {
				object.remove(field);
			} else {
				object.set(field, new JsonMapper().readTree(value));
			}
			String body = object.toString();
			var client = start(exchange -> respond(exchange, 201, "application/json", body));
			assertThatThrownBy(() -> client.createHold(REQUEST)).as("%s = %s", field, value)
					.isInstanceOfSatisfying(CatedraProtocolException.class, CatedraAppointmentHoldClientTest::assertSafe);
		}
	}

	@ParameterizedTest
	@ValueSource(strings = {"CONFIRMED", "held", "PENDING_HOLD"})
	void rejectsAnUnexpectedRemoteStatus(String status) throws IOException {
		var client = start(exchange -> respond(exchange, 201, "application/json", RESPONSE.replace("HELD", status)));
		assertThatThrownBy(() -> client.createHold(REQUEST)).isInstanceOf(CatedraProtocolException.class);
	}

	@Test
	void sanitizesInvalidExpiryIncludingSensitiveRemoteInput() throws IOException {
		var client = start(exchange -> respond(exchange, 201, "application/json",
				RESPONSE.replace("2026-11-17T14:35:00.123456Z", TEST_JWT + PRIVATE_DETAIL)));
		assertThatThrownBy(() -> client.createHold(REQUEST)).isInstanceOfSatisfying(CatedraProtocolException.class,
				CatedraAppointmentHoldClientTest::assertSafe);
	}

	@ParameterizedTest
	@EnumSource(CatedraHoldException.Reason.class)
	void distinguishesEachDocumentedRejectionByItsStatusAndCode(CatedraHoldException.Reason reason) throws IOException {
		var client = start(exchange -> respond(exchange, reason.httpStatus(), "application/problem+json",
				problem(reason.name())));
		assertThatThrownBy(() -> client.createHold(REQUEST)).isInstanceOfSatisfying(CatedraHoldException.class, failure -> {
			assertThat(failure.reason()).isEqualTo(reason);
			assertSafe(failure);
		});
		assertThat(requests.get()).isEqualTo(1);
	}

	@ParameterizedTest
	@CsvSource({"401,INVALID_SLOT", "400,SLOT_ALREADY_HELD", "409,PROFESSIONAL_NOT_FOUND",
			"404,PROFESSIONAL_DISABLED", "403,STUDENT_INTEGRATION_NOT_FOUND", "400,VALIDATION_ERROR",
			"500,INVALID_SLOT", "503,UNKNOWN_CODE"})
	void preservesOtherHttpFailuresInsteadOfInventingAFunctionalRejection(int status, String code) throws IOException {
		var client = start(exchange -> respond(exchange, status, "application/problem+json", problem(code)));
		assertThatThrownBy(() -> client.createHold(REQUEST)).isInstanceOfSatisfying(CatedraHttpException.class, failure -> {
			assertThat(failure.status().value()).isEqualTo(status);
			assertThat(failure.code()).isEqualTo(code);
			assertSafe(failure);
		});
		assertThat(requests.get()).isEqualTo(1);
	}

	@ParameterizedTest
	@ValueSource(ints = {401, 403, 404, 409, 500, 503})
	void doesNotInterpretDetailAsAnErrorCode(int status) throws IOException {
		var client = start(exchange -> respond(exchange, status, "application/problem+json",
				"{\"detail\":\"SLOT_ALREADY_HELD " + TEST_JWT + PRIVATE_DETAIL + "\"}"));
		assertThatThrownBy(() -> client.createHold(REQUEST)).isInstanceOfSatisfying(CatedraHttpException.class, failure -> {
			assertThat(failure.status().value()).isEqualTo(status);
			assertThat(failure.code()).isNull();
			assertSafe(failure);
		});
	}

	@Test
	void rejectsNullRequestBeforeCallingTheServer() throws IOException {
		var client = start(exchange -> respond(exchange, 201, "application/json", RESPONSE));
		assertThatThrownBy(() -> client.createHold(null)).isInstanceOf(NullPointerException.class);
		assertThat(requests.get()).isZero();
	}

	@Test
	void reportsTimeoutWithoutRetryingOrClaimingTheHoldWasRejected() throws IOException {
		String baseUrl = server(exchange -> {
			try {
				Thread.sleep(400);
				respond(exchange, 201, "application/json", RESPONSE);
			} catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
			} catch (IOException exception) {
				// A timed-out caller may have closed the exchange after the remote operation.
			} finally {
				exchange.close();
			}
		});
		var client = client(baseUrl, Duration.ofMillis(100));
		assertThatThrownBy(() -> client.createHold(REQUEST)).isInstanceOfSatisfying(CatedraTimeoutException.class,
				CatedraAppointmentHoldClientTest::assertSafe);
		assertThat(requests.get()).isLessThanOrEqualTo(1);
	}

	@Test
	void distinguishesConnectionFailureFromFunctionalRejection() throws IOException {
		int port;
		try (var socket = new ServerSocket(0)) {
			port = socket.getLocalPort();
		}
		var client = client("http://127.0.0.1:" + port, Duration.ofSeconds(1));
		assertThatThrownBy(() -> client.createHold(REQUEST)).isInstanceOfSatisfying(CatedraTransportException.class,
				CatedraAppointmentHoldClientTest::assertSafe);
	}

	private static String problem(String code) {
		return "{\"code\":\"" + code + "\",\"detail\":\"" + TEST_JWT + PRIVATE_DETAIL + "\"}";
	}

	private CatedraAppointmentHoldClient start(HttpHandler handler) throws IOException {
		return client(server(handler), Duration.ofSeconds(5));
	}

	private String server(HttpHandler handler) throws IOException {
		var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		servers.add(server);
		server.createContext("/", exchange -> {
			requests.incrementAndGet();
			handler.handle(exchange);
		});
		server.start();
		return "http://127.0.0.1:" + server.getAddress().getPort();
	}

	private CatedraAppointmentHoldClient client(String baseUrl, Duration readTimeout) {
		var properties = new CatedraIntegrationProperties("test-project", TEST_JWT,
				new CatedraIntegrationProperties.RestProperties(baseUrl, Duration.ofSeconds(2), readTimeout),
				new CatedraIntegrationProperties.KafkaProperties("kafka.example.test:9092", "test-group", "test.actions", "test.phone"));
		var configuration = new CatedraRestClientConfiguration();
		return configuration.catedraAppointmentHoldClient(configuration.catedraRestClient(properties, new JsonMapper()));
	}

	private static void respond(HttpExchange exchange, int status, String mediaType, String body) throws IOException {
		if (mediaType != null) {
			exchange.getResponseHeaders().set("Content-Type", mediaType);
		}
		if (body.isEmpty()) {
			exchange.sendResponseHeaders(status, -1);
			exchange.close();
			return;
		}
		byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
		exchange.sendResponseHeaders(status, bytes.length);
		try (var output = exchange.getResponseBody()) {
			output.write(bytes);
		}
	}

	private static void assertSafe(Throwable failure) {
		var trace = new StringWriter();
		failure.printStackTrace(new PrintWriter(trace));
		assertThat(trace.toString()).doesNotContain(TEST_JWT, PRIVATE_DETAIL);
		assertThat(failure).hasNoCause();
	}
}
