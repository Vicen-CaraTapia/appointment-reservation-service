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
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import tools.jackson.databind.json.JsonMapper;

import ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.config.CatedraIntegrationProperties;
import ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.rest.config.CatedraRestClientConfiguration;
import ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.rest.dto.CatedraAppointmentOccupanciesResponse.Status;
import ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.rest.exception.CatedraHttpException;
import ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.rest.exception.CatedraProtocolException;
import ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.rest.exception.CatedraTimeoutException;
import ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.rest.exception.CatedraTransportException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ExtendWith(OutputCaptureExtension.class)
class CatedraAppointmentOccupanciesClientTest {

	private static final LocalDate FROM = LocalDate.of(2026, 9, 14);
	private static final LocalDate TO = FROM.plusDays(1);
	private static final String TEST_JWT = "test-only-occupancies-technical-token";
	private static final String RESPONSE = """
			{"professionalId":15,"from":"2026-09-14","to":"2026-09-15","occupancies":[
			 {"date":"2026-09-14","startTime":"09:00:00","status":"CONFIRMED","expiresAt":null},
			 {"date":"2026-09-15","startTime":"09:30:00","status":"HELD","expiresAt":"2026-09-10T14:44:50Z"}]}
			""";
	private final List<HttpServer> servers = new ArrayList<>();
	private final AtomicInteger requests = new AtomicInteger();

	@AfterEach
	void cleanupAndCheckLogs(CapturedOutput output) {
		servers.forEach(server -> server.stop(0));
		assertThat(output.getAll()).doesNotContain(TEST_JWT);
	}

	@Test
	void sendsAuthenticatedGetWithTheContractParametersAndPreservesBothStatuses() throws IOException {
		var authorization = new AtomicReference<String>();
		var path = new AtomicReference<String>();
		var method = new AtomicReference<String>();
		var accept = new AtomicReference<String>();
		var client = start(exchange -> {
			authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
			path.set(exchange.getRequestURI().toString());
			method.set(exchange.getRequestMethod());
			accept.set(exchange.getRequestHeaders().getFirst("Accept"));
			respond(exchange, 200, "application/json", RESPONSE);
		});

		var result = client.getOccupancies(15, FROM, TO);

		assertThat(path.get()).isEqualTo("/api/appointment-occupancies?professionalId=15&from=2026-09-14&to=2026-09-15");
		assertThat(method.get()).isEqualTo("GET");
		assertThat(accept.get()).isEqualTo("application/json");
		assertThat(authorization.get()).isEqualTo("Bearer " + TEST_JWT);
		assertThat(result.professionalId()).isEqualTo(15);
		assertThat(result.from()).isEqualTo(FROM);
		assertThat(result.to()).isEqualTo(TO);
		assertThat(result.occupancies()).extracting(item -> item.status()).containsExactly(Status.CONFIRMED, Status.HELD);
		assertThat(result.occupancies().get(1).expiresAt()).isEqualTo(Instant.parse("2026-09-10T14:44:50Z"));
		assertThat(requests.get()).isEqualTo(1);
	}

	@ParameterizedTest
	@ValueSource(longs = {Long.MIN_VALUE, 0, Long.MAX_VALUE})
	void preservesTheFullInt64IdAndAcceptsASingleDayEmptyResponse(long id) throws IOException {
		var path = new AtomicReference<String>();
		var client = start(exchange -> {
			path.set(exchange.getRequestURI().toString());
			respond(exchange, 200, "application/json", """
					{"professionalId":%d,"from":"2026-09-14","to":"2026-09-14","occupancies":[]}
					""".formatted(id));
		});
		assertThat(client.getOccupancies(id, FROM, FROM).occupancies()).isEmpty();
		assertThat(path.get()).contains("professionalId=" + id);
	}

	@Test
	void rejectsMissingDatesAndReversedRangesBeforeMakingAnyRequest() throws IOException {
		var client = start(exchange -> respond(exchange, 200, "application/json", RESPONSE));
		assertThatThrownBy(() -> client.getOccupancies(15, null, TO)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> client.getOccupancies(15, FROM, null)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> client.getOccupancies(15, TO, FROM)).isInstanceOf(IllegalArgumentException.class);
		assertThat(requests.get()).isZero();
	}

	@ParameterizedTest
	@ValueSource(booleans = {true, false})
	void confirmedPrevailsOverHeldRegardlessOfResponseOrder(boolean confirmedFirst) throws IOException {
		String held = "{\"date\":\"2026-09-14\",\"startTime\":\"09:00:00\",\"status\":\"HELD\"}";
		String confirmed = held.replace("HELD", "CONFIRMED");
		String body = """
				{"professionalId":15,"from":"2026-09-14","to":"2026-09-15","occupancies":[%s,%s]}
				""".formatted(confirmedFirst ? confirmed : held, confirmedFirst ? held : confirmed);
		var client = start(exchange -> respond(exchange, 200, "application/json", body));
		assertThat(client.getOccupancies(15, FROM, TO).occupancies()).singleElement()
				.satisfies(item -> {
					assertThat(item.status()).isEqualTo(Status.CONFIRMED);
					assertThat(item.expiresAt()).isNull();
				});
	}

	@ParameterizedTest
	@ValueSource(strings = {
			"{\"professionalId\":16,\"from\":\"2026-09-14\",\"to\":\"2026-09-15\",\"occupancies\":[]}",
			"{\"professionalId\":15,\"from\":\"2026-09-13\",\"to\":\"2026-09-15\",\"occupancies\":[]}",
			"{\"professionalId\":15,\"from\":\"2026-09-14\",\"to\":\"2026-09-16\",\"occupancies\":[]}",
			"{}", "null", "[]", "", "not-json",
			"{\"professionalId\":15,\"from\":\"2026-09-14\",\"to\":\"2026-09-15\",\"occupancies\":null}"
	})
	void rejectsMalformedMissingOrMismatchedResponses(String body) throws IOException {
		var client = start(exchange -> respond(exchange, 200, "application/json", body));
		assertThatThrownBy(() -> client.getOccupancies(15, FROM, TO))
				.isInstanceOfSatisfying(CatedraProtocolException.class, CatedraAppointmentOccupanciesClientTest::assertSafe);
	}

	@ParameterizedTest
	@ValueSource(strings = {
			"{\"date\":\"2026-09-13\",\"startTime\":\"09:00:00\",\"status\":\"CONFIRMED\"}",
			"{\"date\":\"2026-09-16\",\"startTime\":\"09:00:00\",\"status\":\"HELD\"}",
			"{\"date\":\"2026-09-14\",\"startTime\":\"09:00:00\",\"status\":\"UNKNOWN\"}",
			"{\"date\":\"2026-09-14\",\"startTime\":\"25:00:00\",\"status\":\"HELD\"}",
			"{\"date\":\"2026-09-14\",\"startTime\":\"09:00:00\",\"status\":\"CONFIRMED\",\"expiresAt\":\"2026-09-14T12:00:00Z\"}",
			"{}", "null"
	})
	void rejectsOccupanciesOutsideTheContract(String occupancy) throws IOException {
		String body = """
				{"professionalId":15,"from":"2026-09-14","to":"2026-09-15","occupancies":[%s]}
				""".formatted(occupancy);
		var client = start(exchange -> respond(exchange, 200, "application/json", body));
		assertThatThrownBy(() -> client.getOccupancies(15, FROM, TO))
				.isInstanceOfSatisfying(CatedraProtocolException.class, CatedraAppointmentOccupanciesClientTest::assertSafe);
	}

	@ParameterizedTest
	@ValueSource(ints = {400, 401, 403, 404, 409, 500, 503})
	void preservesHttpStatusAndCodeWithoutUsingOrExposingDetail(int status) throws IOException {
		var client = start(exchange -> respond(exchange, status, "application/problem+json",
				"{\"code\":\"test-code\",\"detail\":\"" + TEST_JWT + "\"}"));
		assertThatThrownBy(() -> client.getOccupancies(15, FROM, TO))
				.isInstanceOfSatisfying(CatedraHttpException.class, failure -> {
					assertThat(failure.status().value()).isEqualTo(status);
					assertThat(failure.code()).isEqualTo("test-code");
					assertSafe(failure);
				});
		assertThat(requests.get()).isEqualTo(1);
	}

	@Test
	void doesNotExposeInvalidRemotePayloadsInExceptions() throws IOException {
		var client = start(exchange -> respond(exchange, 200, "application/json", "{\"occupancies\":\"" + TEST_JWT + "\"}"));
		assertThatThrownBy(() -> client.getOccupancies(15, FROM, TO))
				.isInstanceOfSatisfying(CatedraProtocolException.class, CatedraAppointmentOccupanciesClientTest::assertSafe);
	}

	@Test
	void preservesTheTransportFailureForARefusedConnection() throws IOException {
		int port;
		try (var socket = new ServerSocket(0, 0, java.net.InetAddress.getLoopbackAddress())) {
			port = socket.getLocalPort();
		}
		var client = client("http://127.0.0.1:" + port, Duration.ofSeconds(2));
		assertThatThrownBy(() -> client.getOccupancies(15, FROM, TO))
				.isInstanceOfSatisfying(CatedraTransportException.class, CatedraAppointmentOccupanciesClientTest::assertSafe);
	}

	@Test
	void preservesTheConfiguredTimeoutWithoutRetrying() throws IOException {
		String baseUrl = server(exchange -> {
			try {
				Thread.sleep(400);
				respond(exchange, 200, "application/json", RESPONSE);
			} catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
			} catch (IOException exception) {
				// The timed-out client may already have closed the exchange.
			} finally {
				exchange.close();
			}
		});
		var client = client(baseUrl, Duration.ofMillis(100));
		assertThatThrownBy(() -> client.getOccupancies(15, FROM, TO))
				.isInstanceOfSatisfying(CatedraTimeoutException.class, CatedraAppointmentOccupanciesClientTest::assertSafe);
		assertThat(requests.get()).isLessThanOrEqualTo(1);
	}

	private CatedraAppointmentOccupanciesClient start(HttpHandler handler) throws IOException {
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

	private CatedraAppointmentOccupanciesClient client(String baseUrl, Duration readTimeout) {
		var properties = new CatedraIntegrationProperties("test-project", TEST_JWT,
				new CatedraIntegrationProperties.RestProperties(baseUrl, Duration.ofSeconds(2), readTimeout),
				new CatedraIntegrationProperties.KafkaProperties("kafka.example.test:9092", "test-group", "test.actions", "test.phone"));
		return new CatedraRestClientConfiguration().catedraAppointmentOccupanciesClient(
				new CatedraRestClientConfiguration().catedraRestClient(properties, new JsonMapper()));
	}

	private static void respond(HttpExchange exchange, int status, String mediaType, String body) throws IOException {
		exchange.getResponseHeaders().set("Content-Type", mediaType);
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
		assertThat(trace.toString()).doesNotContain(TEST_JWT);
		assertThat(failure).hasNoCause();
	}
}
