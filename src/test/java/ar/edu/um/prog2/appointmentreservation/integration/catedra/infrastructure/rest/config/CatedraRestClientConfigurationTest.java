package ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.rest.config;

import java.io.IOException;
import java.net.InetSocketAddress;
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
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.config.CatedraIntegrationProperties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ExtendWith(OutputCaptureExtension.class)
class CatedraRestClientConfigurationTest {

	private static final String TEST_JWT = "test-only-catedra-http-token";
	private final List<HttpServer> servers = new ArrayList<>();

	@AfterEach
	void stopServersAndCheckLogs(CapturedOutput output) {
		servers.forEach(server -> server.stop(0));
		assertThat(output.getAll()).doesNotContain(TEST_JWT);
	}

	@Test
	void usesTheConfiguredBasePathAndOverwritesAuthorizationWithTheTechnicalJwt() throws IOException {
		var authorization = new AtomicReference<List<String>>();
		var uri = new AtomicReference<String>();
		String baseUrl = server(exchange -> {
			authorization.set(exchange.getRequestHeaders().get(HttpHeaders.AUTHORIZATION));
			uri.set(exchange.getRequestURI().toString());
			respond(exchange, 200, "{\"value\":\"ok\"}");
		});

		var response = client(baseUrl + "/catedra", Duration.ofSeconds(5)).get()
				.uri("/probe?value=ok")
				.header(HttpHeaders.AUTHORIZATION, "Bearer test-final-user-token")
				.retrieve().body(ProbeResponse.class);

		assertThat(response).isEqualTo(new ProbeResponse("ok"));
		assertThat(uri.get()).isEqualTo("/catedra/probe?value=ok");
		assertThat(authorization.get()).containsExactly("Bearer " + TEST_JWT);
	}

	@Test
	void authenticatesPostAndSerializesItsJsonBody() throws IOException {
		var method = new AtomicReference<String>();
		var body = new AtomicReference<String>();
		var authorization = new AtomicReference<String>();
		String baseUrl = server(exchange -> {
			method.set(exchange.getRequestMethod());
			body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
			authorization.set(exchange.getRequestHeaders().getFirst(HttpHeaders.AUTHORIZATION));
			respond(exchange, 201, "{\"value\":\"created\"}");
		});

		var response = client(baseUrl, Duration.ofSeconds(5)).post().uri("/probe")
				.body(new ProbeResponse("request"))
				.retrieve().toEntity(ProbeResponse.class);

		assertThat(response.getStatusCode().value()).isEqualTo(201);
		assertThat(response.getBody()).isEqualTo(new ProbeResponse("created"));
		assertThat(method.get()).isEqualTo("POST");
		assertThat(body.get()).isEqualTo("{\"value\":\"request\"}");
		assertThat(authorization.get()).isEqualTo("Bearer " + TEST_JWT);
	}

	@Test
	void rejectsRequestsToAnotherOriginBeforeSendingTheTechnicalJwt() throws IOException {
		var requests = new AtomicInteger();
		String baseUrl = server(exchange -> respond(exchange, 200, "{}"));
		String otherOrigin = server(exchange -> {
			requests.incrementAndGet();
			respond(exchange, 200, "{}");
		});

		assertThatThrownBy(() -> client(baseUrl, Duration.ofSeconds(5)).get()
				.uri(otherOrigin + "/probe").retrieve().toBodilessEntity())
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("Catedra requests must use the configured REST origin");
		assertThat(requests.get()).isZero();
	}

	@Test
	void doesNotFollowRedirects() throws IOException {
		var redirectedRequests = new AtomicInteger();
		String destination = server(exchange -> {
			redirectedRequests.incrementAndGet();
			respond(exchange, 200, "{}");
		});
		String baseUrl = server(exchange -> {
			exchange.getResponseHeaders().set(HttpHeaders.LOCATION, destination + "/probe");
			exchange.sendResponseHeaders(302, -1);
			exchange.close();
		});

		var response = client(baseUrl, Duration.ofSeconds(5)).get().uri("/redirect")
				.retrieve().toBodilessEntity();

		assertThat(response.getStatusCode().value()).isEqualTo(302);
		assertThat(redirectedRequests.get()).isZero();
	}

	@Test
	void appliesTheConfiguredReadTimeout() throws IOException {
		String baseUrl = server(exchange -> {
			try {
				Thread.sleep(250);
				respond(exchange, 200, "{}");
			}
			catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
			}
			catch (IOException exception) {
				// The caller has already timed out and closed its connection.
			}
			finally {
				exchange.close();
			}
		});

		assertThatThrownBy(() -> client(baseUrl, Duration.ofMillis(25)).get().uri("/slow")
				.retrieve().toBodilessEntity())
				.isInstanceOf(ResourceAccessException.class)
				.hasCauseInstanceOf(java.net.http.HttpTimeoutException.class)
				.hasMessageNotContaining(TEST_JWT);
	}

	@ParameterizedTest
	@ValueSource(strings = {
			"not a URI", "/relative", "ftp://example.test", "https://example.test:0",
			"https://example.test:65536", "https://user:secret-value@example.test",
			"https://example.test?key=secret-value", "https://example.test#fragment"
	})
	void rejectsInvalidBaseUrlsWithoutEchoingTheirValues(String baseUrl) {
		assertThatThrownBy(() -> client(baseUrl, Duration.ofSeconds(5)))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("Catedra REST base URL must be an absolute HTTP(S) URL without credentials, query or fragment")
				.hasMessageNotContaining("secret-value")
				.hasNoCause();
	}

	private String server(HttpHandler handler) throws IOException {
		HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		servers.add(server);
		server.createContext("/", handler);
		server.start();
		return "http://127.0.0.1:" + server.getAddress().getPort();
	}

	private RestClient client(String baseUrl, Duration readTimeout) {
		var properties = new CatedraIntegrationProperties("test-project", TEST_JWT,
				new CatedraIntegrationProperties.RestProperties(baseUrl, Duration.ofSeconds(5), readTimeout),
				new CatedraIntegrationProperties.KafkaProperties("kafka.example.test:9092", "test-group",
						"test.appointment.actions", "test.appointment.phone"));
		return new CatedraRestClientConfiguration().catedraHttpClient(properties);
	}

	private static void respond(HttpExchange exchange, int status, String body) throws IOException {
		byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().set(HttpHeaders.CONTENT_TYPE, "application/json");
		exchange.sendResponseHeaders(status, bytes.length);
		try (var output = exchange.getResponseBody()) {
			output.write(bytes);
		}
	}

	private record ProbeResponse(String value) {
	}
}
