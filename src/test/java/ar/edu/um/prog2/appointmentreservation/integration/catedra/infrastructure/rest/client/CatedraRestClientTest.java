package ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.rest.client;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.config.CatedraIntegrationProperties;
import ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.rest.config.CatedraRestClientConfiguration;
import ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.rest.dto.CatedraProblemDetails;
import ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.rest.exception.CatedraHttpException;
import ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.rest.exception.CatedraProtocolException;
import ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.rest.exception.CatedraTimeoutException;
import ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.rest.exception.CatedraTransportException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ExtendWith(OutputCaptureExtension.class)
class CatedraRestClientTest {

	private static final String TEST_JWT = "test-only-private-catedra-token";
	private static final String PRIVATE_DETAIL = "remote-private-diagnostic";
	private final List<HttpServer> servers = new ArrayList<>();

	@AfterEach
	void cleanupAndCheckLogs(CapturedOutput output) {
		servers.forEach(server -> server.stop(0));
		assertThat(output.getAll()).doesNotContain(TEST_JWT, PRIVATE_DETAIL);
	}

	@Test
	void getUsesTheProductionAuthenticatedClientAndReadsJson() throws IOException {
		var authorization = new AtomicReference<String>();
		var client = start(exchange -> {
			authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
			respond(exchange, 200, "application/json", "{\"value\":\"ok\"}");
		});

		assertThat(client.get("/probe", Probe.class)).isEqualTo(new Probe("ok"));
		assertThat(authorization.get()).isEqualTo("Bearer " + TEST_JWT);
	}

	@Test
	void postSendsAuthenticatedJsonAndReadsACreatedResponse() throws IOException {
		var body = new AtomicReference<String>();
		var method = new AtomicReference<String>();
		var authorization = new AtomicReference<String>();
		var client = start(exchange -> {
			method.set(exchange.getRequestMethod());
			body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
			authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
			respond(exchange, 201, "application/json", "{\"value\":\"created\"}");
		});

		assertThat(client.post("/probe", new Probe("request"), Probe.class)).isEqualTo(new Probe("created"));
		assertThat(method.get()).isEqualTo("POST");
		assertThat(body.get()).isEqualTo("{\"value\":\"request\"}");
		assertThat(authorization.get()).isEqualTo("Bearer " + TEST_JWT);
	}

	@Test
	void acceptsAnExplicitlyBodilessSuccess() throws IOException {
		var client = start(exchange -> respond(exchange, 204, null, ""));
		assertThat(client.post("/probe", new Probe("request"), Void.class)).isNull();
	}

	@ParameterizedTest
	@ValueSource(ints = {400, 401, 403, 404, 409, 500, 503})
	void preservesTheRealHttpStatusAndStableCodeWithoutUsingDetail(int status) throws IOException {
		var requests = new AtomicInteger();
		var client = start(exchange -> {
			requests.incrementAndGet();
			respond(exchange, status, "application/problem+json; charset=UTF-8",
					"{\"status\":200,\"code\":\"STABLE_CODE\",\"detail\":\"" + PRIVATE_DETAIL + TEST_JWT
					+ "\",\"unknown\":{\"additional\":true}}");
		});

		assertThatThrownBy(() -> client.get("/probe", Probe.class))
				.isInstanceOfSatisfying(CatedraHttpException.class, error -> {
					assertThat(error.status().value()).isEqualTo(status);
					assertThat(error.code()).isEqualTo("STABLE_CODE");
					assertSafe(error);
				});
		assertThat(requests.get()).isEqualTo(1);
	}

	@ParameterizedTest
	@ValueSource(ints = {401, 403, 500, 503})
	void acceptsErrorsWithoutFunctionalCode(int status) throws IOException {
		var client = start(exchange -> respond(exchange, status, "application/problem+json",
				"{\"detail\":\"" + PRIVATE_DETAIL + "\"}"));
		assertThatThrownBy(() -> client.get("/probe", Probe.class))
				.isInstanceOfSatisfying(CatedraHttpException.class, error -> {
					assertThat(error.status().value()).isEqualTo(status);
					assertThat(error.code()).isNull();
					assertSafe(error);
				});
	}

	@ParameterizedTest
	@ValueSource(strings = {"", "{invalid", "[]", "null", "{\"code\":17}", "{\"code\":{}}", "{\"code\":\" \"}"})
	void preservesStatusWhenProblemDataIsMissingOrMalformed(String body) throws IOException {
		var client = start(exchange -> respond(exchange, 409, "application/problem+json", body));
		assertThatThrownBy(() -> client.post("/probe", new Probe("request"), Probe.class))
				.isInstanceOfSatisfying(CatedraHttpException.class, error -> {
					assertThat(error.status().value()).isEqualTo(409);
					assertThat(error.code()).isNull();
					assertSafe(error);
				});
	}

	@ParameterizedTest
	@ValueSource(strings = {"text/plain", "application/json", "invalid-media-type"})
	void preservesStatusWithoutInterpretingNonProblemResponses(String mediaType) throws IOException {
		var client = start(exchange -> respond(exchange, 503, mediaType, "{\"code\":\"IGNORED_CODE\"}"));
		assertThatThrownBy(() -> client.get("/probe", Probe.class))
				.isInstanceOfSatisfying(CatedraHttpException.class, error -> {
					assertThat(error.status().value()).isEqualTo(503);
					assertThat(error.code()).isNull();
				});
	}

	@Test
	void ignoresOversizedProblemBodiesWithoutLosingTheStatus() throws IOException {
		String body = "{\"code\":\"IGNORED_CODE\",\"detail\":\"" + "x".repeat(65536) + "\"}";
		var client = start(exchange -> respond(exchange, 500, "application/problem+json", body));
		assertThatThrownBy(() -> client.get("/probe", Probe.class))
				.isInstanceOfSatisfying(CatedraHttpException.class, error -> {
					assertThat(error.status().value()).isEqualTo(500);
					assertThat(error.code()).isNull();
				});
	}

	@ParameterizedTest
	@ValueSource(strings = {"", "{invalid", "[]"})
	void translatesInvalidSuccessBodiesWithoutLeakingPayloads(String body) throws IOException {
		var client = start(exchange -> respond(exchange, 200, "application/json", body));
		assertThatThrownBy(() -> client.get("/probe", Probe.class))
				.isInstanceOfSatisfying(CatedraProtocolException.class, CatedraRestClientTest::assertSafe);
	}

	@Test
	void doesNotExposeSensitiveConversionFailures() throws IOException {
		var client = start(exchange -> respond(exchange, 200, "application/json",
				"{\"value\":{\"sensitive\":\"" + TEST_JWT + PRIVATE_DETAIL + "\"}}"));
		assertThatThrownBy(() -> client.get("/probe", Probe.class))
				.isInstanceOfSatisfying(CatedraProtocolException.class, CatedraRestClientTest::assertSafe);
	}

	@Test
	void rejectsEmptyResponsesWhenABodyWasRequired() throws IOException {
		var client = start(exchange -> respond(exchange, 204, null, ""));
		assertThatThrownBy(() -> client.get("/probe", Probe.class)).isInstanceOf(CatedraProtocolException.class);
	}

	@Test
	void translatesTimeoutWithoutRetryingAPostOrAssumingItsOutcome() throws IOException {
		var requests = new AtomicInteger();
		var client = start(exchange -> {
			requests.incrementAndGet();
			try {
				Thread.sleep(400);
				respond(exchange, 201, "application/json", "{\"value\":\"created\"}");
			}
			catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
			}
			catch (IOException exception) {
				// The caller has already timed out.
			}
			finally {
				exchange.close();
			}
		}, Duration.ofMillis(150));

		assertThatThrownBy(() -> client.post("/probe", new Probe("request"), Probe.class))
				.isInstanceOfSatisfying(CatedraTimeoutException.class, CatedraRestClientTest::assertSafe);
		assertThat(requests.get()).isEqualTo(1);
	}

	@Test
	void distinguishesTransportFailureFromTimeout() throws IOException {
		int unusedPort;
		try (var socket = new ServerSocket(0)) {
			unusedPort = socket.getLocalPort();
		}
		var client = client("http://127.0.0.1:" + unusedPort, Duration.ofSeconds(2));
		assertThatThrownBy(() -> client.get("/probe", Probe.class))
				.isInstanceOfSatisfying(CatedraTransportException.class, CatedraRestClientTest::assertSafe);
	}

	@Test
	void alsoRecognizesTimeoutDuringResponseBodyDecoding() {
		var http = RestClient.builder().requestFactory((uri, method) -> {
			var request = new MockClientHttpRequest(method, uri);
			var response = new MockClientHttpResponse(new InputStream() {
				@Override
				public int read() throws IOException {
					throw new SocketTimeoutException(TEST_JWT + PRIVATE_DETAIL);
				}
			}, HttpStatus.OK);
			response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
			request.setResponse(response);
			return request;
		}).build();
		var client = new CatedraRestClient(http, new JsonMapper());
		assertThatThrownBy(() -> client.get("/probe", Probe.class))
				.isInstanceOfSatisfying(CatedraTimeoutException.class, CatedraRestClientTest::assertSafe);
	}

	@Test
	void treatsRedirectAsAnUnexpectedHttpStatusWithoutFollowingIt() throws IOException {
		var requests = new AtomicInteger();
		var client = start(exchange -> {
			requests.incrementAndGet();
			exchange.getResponseHeaders().set("Location", "/other");
			respond(exchange, 302, null, "");
		});
		assertThatThrownBy(() -> client.get("/probe", Probe.class))
				.isInstanceOfSatisfying(CatedraHttpException.class,
						error -> assertThat(error.status().value()).isEqualTo(302));
		assertThat(requests.get()).isEqualTo(1);
	}

	@ParameterizedTest
	@ValueSource(strings = {"https://example.test/private", "//example.test/private", "/probe#fragment", "/%invalid"})
	void rejectsNonRelativeOrInvalidRequestPaths(String path) throws IOException {
		var requests = new AtomicInteger();
		var client = start(exchange -> {
			requests.incrementAndGet();
			respond(exchange, 200, "application/json", "{}");
		});
		assertThatThrownBy(() -> client.get(path, Probe.class))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("Catedra request path must be relative and have no authority or fragment")
				.hasNoCause();
		assertThat(requests.get()).isZero();
	}

	@Test
	void doesNotIncludeARemoteCodeInExceptionOrDtoStringRepresentations() {
		var problem = new CatedraProblemDetails(TEST_JWT);
		var error = new CatedraHttpException(org.springframework.http.HttpStatus.UNAUTHORIZED, problem.code());
		assertThat(problem.toString()).doesNotContain(TEST_JWT);
		assertSafe(error);
	}

	private CatedraRestClient start(HttpHandler handler) throws IOException {
		return start(handler, Duration.ofSeconds(5));
	}

	private CatedraRestClient start(HttpHandler handler, Duration readTimeout) throws IOException {
		var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		servers.add(server);
		server.createContext("/", handler);
		server.start();
		return client("http://127.0.0.1:" + server.getAddress().getPort(), readTimeout);
	}

	private CatedraRestClient client(String baseUrl, Duration readTimeout) {
		var properties = new CatedraIntegrationProperties("test-project", TEST_JWT,
				new CatedraIntegrationProperties.RestProperties(baseUrl, Duration.ofSeconds(2), readTimeout),
				new CatedraIntegrationProperties.KafkaProperties("kafka.example.test:9092", "test-group",
						"test.appointment.actions", "test.appointment.phone"));
		return new CatedraRestClientConfiguration().catedraRestClient(properties, new JsonMapper());
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

	private static void assertSafe(Throwable error) {
		var trace = new StringWriter();
		error.printStackTrace(new PrintWriter(trace));
		assertThat(trace.toString()).doesNotContain(TEST_JWT, PRIVATE_DETAIL);
		assertThat(error).hasNoCause();
	}

	private record Probe(String value) {
	}
}
