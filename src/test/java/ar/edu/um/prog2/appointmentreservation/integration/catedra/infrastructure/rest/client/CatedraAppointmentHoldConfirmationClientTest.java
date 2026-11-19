package ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.rest.client;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
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
import tools.jackson.databind.node.ObjectNode;

import ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.config.CatedraIntegrationProperties;
import ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.rest.config.CatedraRestClientConfiguration;
import ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.rest.dto.CatedraAppointmentHoldConfirmationRequest;
import ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.rest.exception.CatedraHoldConfirmationException;
import ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.rest.exception.CatedraHttpException;
import ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.rest.exception.CatedraProtocolException;
import ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.rest.exception.CatedraTimeoutException;
import ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.rest.exception.CatedraTransportException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ExtendWith(OutputCaptureExtension.class)
class CatedraAppointmentHoldConfirmationClientTest {

	private static final String TEST_JWT = "test-only-confirmation-technical-token";
	private static final String PRIVATE_DETAIL = "private-remote-diagnostic";
	private static final String FIRST = "PrivatePatientFirst";
	private static final String LAST = "PrivatePatientLast";
	private static final CatedraAppointmentHoldConfirmationRequest REQUEST =
			new CatedraAppointmentHoldConfirmationRequest("user-42", FIRST, LAST);
	private static final String RESPONSE = """
			{"reservationProcessId":"process-opaque","status":"WAITING_FOR_PHONE",
			 "expiresAt":"2026-11-19T14:35:00.123456789Z"}
			""";
	private final JsonMapper json = new JsonMapper();
	private final List<HttpServer> servers = new ArrayList<>();
	private final AtomicInteger requests = new AtomicInteger();

	@AfterEach
	void stopServersAndCheckLogs(CapturedOutput output) {
		servers.forEach(server -> server.stop(0));
		assertThat(output.getAll()).doesNotContain(TEST_JWT, PRIVATE_DETAIL, FIRST, LAST);
	}

	@Test
	void postsTheExactContractWithTechnicalBearerAndPreservesTheConfiguredBasePath() throws IOException {
		var method = new AtomicReference<String>();
		var path = new AtomicReference<String>();
		var authorization = new AtomicReference<String>();
		var accept = new AtomicReference<String>();
		var contentType = new AtomicReference<String>();
		var body = new AtomicReference<String>();
		String base = server(exchange -> {
			method.set(exchange.getRequestMethod());
			path.set(exchange.getRequestURI().getRawPath());
			authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
			accept.set(exchange.getRequestHeaders().getFirst("Accept"));
			contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
			body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
			respond(exchange, 202, "application/json", RESPONSE);
		});
		var result = client(base + "/gateway/", Duration.ofSeconds(5)).confirm("hold-opaque", "process-opaque", REQUEST);
		assertThat(method.get()).isEqualTo("POST");
		assertThat(path.get()).isEqualTo("/gateway/api/appointment-holds/hold-opaque/confirm");
		assertThat(authorization.get()).isEqualTo("Bearer " + TEST_JWT);
		assertThat(accept.get()).isEqualTo("application/json");
		assertThat(contentType.get()).startsWith("application/json");
		assertThat(json.readTree(body.get())).isEqualTo(json.readTree(json.writeValueAsString(REQUEST)));
		assertThat(result.reservationProcessId()).isEqualTo("process-opaque");
		assertThat(result.status()).isEqualTo("WAITING_FOR_PHONE");
		assertThat(result.expiresAt()).isEqualTo(Instant.parse("2026-11-19T14:35:00.123456789Z"));
		assertThat(requests.get()).isEqualTo(1);
	}

	@ParameterizedTest
	@CsvSource(delimiter = '|', value = {
			"hold/opaque?x=1#fragment|hold%2Fopaque%3Fx=1%23fragment",
			"hold%2Fother|hold%252Fother",
			"hold{variable}|hold%7Bvariable%7D",
			"..|%2E%2E",
			".|%2E",
			"//other.example/path|%2F%2Fother%2Eexample%2Fpath",
			"hold with space|hold%20with%20space"
	})
	void encodesOpaqueHoldIdentifiersAsOneSegmentExactlyOnce(String hold, String expectedSegment) throws IOException {
		var path = new AtomicReference<String>();
		var query = new AtomicReference<String>();
		var client = start(exchange -> {
			path.set(exchange.getRequestURI().getRawPath());
			query.set(exchange.getRequestURI().getRawQuery());
			respond(exchange, 202, "application/json", RESPONSE);
		});
		client.confirm(hold, "process-opaque", REQUEST);
		assertThat(path.get()).isEqualTo("/api/appointment-holds/" + expectedSegment + "/confirm");
		assertThat(query.get()).isNull();
		assertThat(requests.get()).isEqualTo(1);
	}

	@Test
	void toleratesAdditionalFieldsAndOpaqueProcessIdsAndResolvesOffsetExpiry() throws IOException {
		String process = " process/opaque:" + "p".repeat(300);
		String response = RESPONSE.replace("process-opaque", process)
				.replace("2026-11-19T14:35:00.123456789Z", "2026-11-19T11:35:00.123456789-03:00")
				.replace("\"status\":", "\"futureField\":{\"v\":2},\"status\":");
		var result = start(exchange -> respond(exchange, 202, "application/json", response)).confirm("hold", process, REQUEST);
		assertThat(result.reservationProcessId()).isEqualTo(process);
		assertThat(result.expiresAt()).isEqualTo(Instant.parse("2026-11-19T14:35:00.123456789Z"));
	}

	@ParameterizedTest
	@ValueSource(ints = {200, 201, 204, 207})
	void rejectsUnexpectedSuccessStatusesWithoutRepeatingThePost(int status) throws IOException {
		var client = start(exchange -> respond(exchange, status, "application/json", status == 204 ? "" : RESPONSE));
		assertThatThrownBy(() -> confirm(client)).isInstanceOfSatisfying(CatedraProtocolException.class,
				CatedraAppointmentHoldConfirmationClientTest::assertSafe);
		assertThat(requests.get()).isEqualTo(1);
	}

	@ParameterizedTest
	@NullSource
	@ValueSource(strings = {"text/plain", "application/problem+json"})
	void rejectsIncorrectOrMissingSuccessContentType(String mediaType) throws IOException {
		var client = start(exchange -> respond(exchange, 202, mediaType, RESPONSE));
		assertThatThrownBy(() -> confirm(client)).isInstanceOf(CatedraProtocolException.class);
	}

	@ParameterizedTest
	@ValueSource(strings = {"", "null", "[]", "42", "{broken"})
	void rejectsMalformedOrNonObjectSuccessBodies(String body) throws IOException {
		var client = start(exchange -> respond(exchange, 202, "application/json", body));
		assertThatThrownBy(() -> confirm(client)).isInstanceOfSatisfying(CatedraProtocolException.class,
				CatedraAppointmentHoldConfirmationClientTest::assertSafe);
	}

	@ParameterizedTest
	@ValueSource(strings = {"reservationProcessId", "status", "expiresAt"})
	void rejectsMissingNullOrNonTextualRequiredWireFields(String field) throws IOException {
		for (String value : List.of("missing", "null", "42", "true", "[]", "{}", "\"\"", "\" \"")) {
			var body = (ObjectNode) json.readTree(RESPONSE);
			if (value.equals("missing")) {
				body.remove(field);
			} else {
				body.set(field, json.readTree(value));
			}
			var client = start(exchange -> respond(exchange, 202, "application/json", body.toString()));
			assertThatThrownBy(() -> confirm(client)).as("%s = %s", field, value)
					.isInstanceOfSatisfying(CatedraProtocolException.class, CatedraAppointmentHoldConfirmationClientTest::assertSafe);
		}
	}

	@ParameterizedTest
	@ValueSource(strings = {"HELD", "PHONE_PENDING", "CONFIRMED", "waiting_for_phone", " WAITING_FOR_PHONE "})
	void doesNotInterpretAnotherStatusAsInitialAcceptance(String status) throws IOException {
		var client = start(exchange -> respond(exchange, 202, "application/json", RESPONSE.replace("WAITING_FOR_PHONE", status)));
		assertThatThrownBy(() -> confirm(client)).isInstanceOf(CatedraProtocolException.class);
	}

	@Test
	void rejectsAnUnrelatedExternalProcessWithoutExposingTheRemoteInput() throws IOException {
		var client = start(exchange -> respond(exchange, 202, "application/json", RESPONSE.replace("process-opaque", TEST_JWT + FIRST)));
		assertThatThrownBy(() -> confirm(client)).isInstanceOfSatisfying(CatedraProtocolException.class,
				CatedraAppointmentHoldConfirmationClientTest::assertSafe);
	}

	@ParameterizedTest
	@ValueSource(strings = {"not-an-instant", "2026-11-19T14:35:00"})
	void rejectsMalformedOrNonAbsoluteExpiry(String expiry) throws IOException {
		var client = start(exchange -> respond(exchange, 202, "application/json", RESPONSE.replace("2026-11-19T14:35:00.123456789Z", expiry)));
		assertThatThrownBy(() -> confirm(client)).isInstanceOf(CatedraProtocolException.class);
	}

	@Test
	void sanitizesExpirationParserFailuresContainingSensitiveRemoteValues() throws IOException {
		var client = start(exchange -> respond(exchange, 202, "application/json",
				RESPONSE.replace("2026-11-19T14:35:00.123456789Z", TEST_JWT + PRIVATE_DETAIL + FIRST)));
		assertThatThrownBy(() -> confirm(client)).isInstanceOfSatisfying(CatedraProtocolException.class,
				CatedraAppointmentHoldConfirmationClientTest::assertSafe);
	}

	@ParameterizedTest
	@EnumSource(CatedraHoldConfirmationException.Reason.class)
	void distinguishesEachOfficialRejectionByStatusAndCode(CatedraHoldConfirmationException.Reason reason) throws IOException {
		var client = start(exchange -> respond(exchange, reason.httpStatus(), "application/problem+json", problem(reason.name())));
		assertThatThrownBy(() -> confirm(client)).isInstanceOfSatisfying(CatedraHoldConfirmationException.class, failure -> {
			assertThat(failure.reason()).isEqualTo(reason);
			assertSafe(failure);
		});
		assertThat(requests.get()).isEqualTo(1);
	}

	@ParameterizedTest
	@CsvSource({"401,HOLD_EXPIRED", "403,STUDENT_INTEGRATION_NOT_FOUND", "400,HOLD_NOT_FOUND",
			"409,HOLD_OWNER_MISMATCH", "404,RESERVATION_PROCESS_ALREADY_CONFIRMED", "400,VALIDATION_ERROR",
			"500,INVALID_SLOT", "503,UNKNOWN_CODE"})
	void preservesOtherHttpFailuresWithoutInventingABusinessCause(int status, String code) throws IOException {
		var client = start(exchange -> respond(exchange, status, "application/problem+json", problem(code)));
		assertThatThrownBy(() -> confirm(client)).isInstanceOfSatisfying(CatedraHttpException.class, failure -> {
			assertThat(failure.status().value()).isEqualTo(status);
			assertThat(failure.code()).isEqualTo(code);
			assertSafe(failure);
		});
	}

	@ParameterizedTest
	@ValueSource(ints = {401, 403, 404, 409, 500, 503})
	void doesNotInferFunctionalErrorsFromDetail(int status) throws IOException {
		var client = start(exchange -> respond(exchange, status, "application/problem+json",
				"{\"detail\":\"HOLD_EXPIRED " + TEST_JWT + PRIVATE_DETAIL + FIRST + "\"}"));
		assertThatThrownBy(() -> confirm(client)).isInstanceOfSatisfying(CatedraHttpException.class, failure -> {
			assertThat(failure.status().value()).isEqualTo(status);
			assertThat(failure.code()).isNull();
			assertSafe(failure);
		});
	}

	@Test
	void doesNotFollowRedirectsOrForwardTechnicalCredentialsToTheirDestination() throws IOException {
		var destinationRequests = new AtomicInteger();
		String destination = server(exchange -> {
			destinationRequests.incrementAndGet();
			respond(exchange, 202, "application/json", RESPONSE);
		});
		var client = start(exchange -> {
			exchange.getResponseHeaders().set("Location", destination + "/confirm");
			respond(exchange, 307, "application/json", "{}");
		});
		assertThatThrownBy(() -> confirm(client)).isInstanceOf(CatedraHttpException.class);
		assertThat(destinationRequests.get()).isZero();
		assertThat(requests.get()).isEqualTo(1);
	}

	@Test
	void rejectsMissingLocalInputsBeforeSendingAnyRequest() throws IOException {
		var client = start(exchange -> respond(exchange, 202, "application/json", RESPONSE));
		assertThatThrownBy(() -> client.confirm("hold", "process", null)).isInstanceOf(NullPointerException.class);
		for (String id : new String[] {null, "", " \t"}) {
			assertThatThrownBy(() -> client.confirm(id, "process", REQUEST)).isInstanceOf(IllegalArgumentException.class);
			assertThatThrownBy(() -> client.confirm("hold", id, REQUEST)).isInstanceOf(IllegalArgumentException.class);
		}
		assertThat(requests.get()).isZero();
	}

	@Test
	void timeoutDoesNotRetryOrBecomeARejectionOrAFinalConfirmation() throws IOException {
		String base = server(exchange -> {
			try {
				Thread.sleep(500);
				respond(exchange, 202, "application/json", RESPONSE);
			} catch (InterruptedException failure) {
				Thread.currentThread().interrupt();
			} catch (IOException failure) {
				// The caller may already have timed out after the remote side accepted the request.
			} finally {
				exchange.close();
			}
		});
		var client = client(base, Duration.ofMillis(150));
		assertThatThrownBy(() -> confirm(client)).isInstanceOfSatisfying(CatedraTimeoutException.class,
				CatedraAppointmentHoldConfirmationClientTest::assertSafe);
		assertThat(requests.get()).isLessThanOrEqualTo(1);
	}

	@Test
	void aLostResponseIsATransportFailureWithoutRepeatingThePost() throws IOException {
		var client = start(exchange -> {
			exchange.getRequestBody().readAllBytes();
			exchange.close();
		});
		assertThatThrownBy(() -> confirm(client)).isInstanceOfSatisfying(CatedraTransportException.class,
				CatedraAppointmentHoldConfirmationClientTest::assertSafe);
		assertThat(requests.get()).isEqualTo(1);
	}

	@Test
	void distinguishesAConnectionFailureFromAFunctionalRejection() throws IOException {
		int port;
		try (var socket = new ServerSocket(0)) {
			port = socket.getLocalPort();
		}
		var client = client("http://127.0.0.1:" + port, Duration.ofSeconds(1));
		assertThatThrownBy(() -> confirm(client)).isInstanceOfSatisfying(CatedraTransportException.class,
				CatedraAppointmentHoldConfirmationClientTest::assertSafe);
	}

	private static void confirm(CatedraAppointmentHoldConfirmationClient client) {
		client.confirm("hold-opaque", "process-opaque", REQUEST);
	}

	private static String problem(String code) {
		return "{\"code\":\"" + code + "\",\"detail\":\"" + TEST_JWT + PRIVATE_DETAIL + FIRST + LAST + "\"}";
	}

	private CatedraAppointmentHoldConfirmationClient start(HttpHandler handler) throws IOException {
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

	private CatedraAppointmentHoldConfirmationClient client(String baseUrl, Duration readTimeout) {
		var properties = new CatedraIntegrationProperties("test-project", TEST_JWT,
				new CatedraIntegrationProperties.RestProperties(baseUrl, Duration.ofSeconds(2), readTimeout),
				new CatedraIntegrationProperties.KafkaProperties("kafka.example.test:9092", "test-group", "test.actions", "test.phone"));
		var config = new CatedraRestClientConfiguration();
		return config.catedraAppointmentHoldConfirmationClient(config.catedraRestClient(properties, json));
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
		assertThat(trace.toString()).doesNotContain(TEST_JWT, PRIVATE_DETAIL, FIRST, LAST);
		assertThat(failure).hasNoCause();
	}
}
