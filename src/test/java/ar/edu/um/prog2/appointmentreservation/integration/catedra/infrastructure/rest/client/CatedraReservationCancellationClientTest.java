package ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.rest.client;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import tools.jackson.databind.json.JsonMapper;
import ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.config.CatedraIntegrationProperties;
import ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.rest.config.CatedraRestClientConfiguration;
import ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.rest.adapter.CatedraReservationCancellationAdapter;
import ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.rest.exception.CatedraProtocolException;
import ar.edu.um.prog2.appointmentreservation.reservation.application.exception.ReservationCancellationException;
import static org.assertj.core.api.Assertions.*;

@ExtendWith(OutputCaptureExtension.class)
class CatedraReservationCancellationClientTest {
	private static final String JWT = "test-only-cancellation-token";
	private static final String PRIVATE_REASON = "Private cancellation reason + ñ";
	private static final String RESPONSE = """
		{"reservationId":9223372036854775807,"reservationProcessId":"process-1",
		"status":"CANCELLED","cancelledAt":"2026-11-22T12:00:00Z"}
		""";
	private HttpServer server;
	private URI uri;
	private String authorization;
	private String method;
	private String requestBody;
	private final AtomicInteger requests = new AtomicInteger();

	@AfterEach
	void stopAndCheckLogs(CapturedOutput output) {
		if (server != null) { server.stop(0); }
		assertThat(output.getAll()).doesNotContain(JWT, PRIVATE_REASON, "private diagnostic");
	}

	@Test
	void sendsOneAuthenticatedPostWithoutABodyAndMapsTheResult() throws Exception {
		var client = start(200, "application/json", RESPONSE, Duration.ofSeconds(2), 0);
		var result = new CatedraReservationCancellationAdapter(client).cancel("process-1", null);
		assertThat(method).isEqualTo("POST");
		assertThat(uri.toString()).isEqualTo("/base/api/appointments/process-1/cancel");
		assertThat(authorization).isEqualTo("Bearer " + JWT);
		assertThat(requestBody).isEmpty();
		assertThat(result.reservationId()).isEqualTo(Long.MAX_VALUE);
		assertThat(result.reservationProcessId()).isEqualTo("process-1");
		assertThat(result.cancelledAt()).isEqualTo(Instant.parse("2026-11-22T12:00:00Z"));
		assertThat(result.toString()).doesNotContain("process-1");
		assertThat(requests).hasValue(1);
	}

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = {" ", PRIVATE_REASON})
	void preservesOptionalReasonExactly(String reason) throws Exception {
		var client = start(200, "application/json", RESPONSE, Duration.ofSeconds(2), 0);
		var result = client.cancel("process-1", reason);
		if (reason == null) { assertThat(requestBody).isEmpty(); }
		else { assertThat(new JsonMapper().readTree(requestBody).get("reason").asString()).isEqualTo(reason); }
		assertThat(result.toString()).doesNotContain(PRIVATE_REASON);
	}

	@Test
	void accepts500CharactersButRejects501BeforeSending() throws Exception {
		var client = start(200, "application/json", RESPONSE, Duration.ofSeconds(2), 0);
		client.cancel("process-1", "a".repeat(500));
		assertThatThrownBy(() -> client.cancel("process-1", "a".repeat(501)))
				.isInstanceOf(IllegalArgumentException.class).hasMessageNotContaining("a".repeat(501));
		assertThat(requests).hasValue(1);
	}

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = {" ", "\t"})
	void rejectsMissingProcessWithoutRequests(String id) throws Exception {
		var client = start(200, "application/json", RESPONSE, Duration.ofSeconds(2), 0);
		assertThatThrownBy(() -> client.cancel(id, null)).isInstanceOf(IllegalArgumentException.class).hasNoCause();
		assertThat(requests).hasValue(0);
	}

	@ParameterizedTest
	@ValueSource(strings = {"../process", "..", "opaque/a?b#c%20+&={ñ}", "https://other.invalid/path", "with space", "opaque;v=x:@user"})
	void treatsProcessAsOneOpaqueSegmentWithoutDoubleEncoding(String id) throws Exception {
		String body = new JsonMapper().writeValueAsString(java.util.Map.of("reservationId", 1,
				"reservationProcessId", id, "status", "CANCELLED", "cancelledAt", "2026-11-22T12:00:00Z"));
		var client = start(200, "application/json", body, Duration.ofSeconds(2), 0);
		assertThat(client.cancel(id, null).reservationProcessId()).isEqualTo(id);
		assertThat(uri.getRawQuery()).isNull(); assertThat(uri.getRawFragment()).isNull();
		String segment = uri.getRawPath().substring("/base/api/appointments/".length());
		segment = segment.substring(0, segment.length() - "/cancel".length());
		assertThat(segment).doesNotContain("/", ".", "?", "#", "{", ";", ":", "@", "=");
		assertThat(URLDecoder.decode(segment.replace("+", "%2B"), StandardCharsets.UTF_8)).isEqualTo(id);
		assertThat(requests).hasValue(1);
	}

	@Test
	void acceptsTheOfficialAlreadyCancelledResponseWithoutInventingLocalState() throws Exception {
		var client = start(200, "application/json", RESPONSE, Duration.ofSeconds(2), 0);
		assertThat(client.cancel("process-1", PRIVATE_REASON)).isEqualTo(client.cancel("process-1", null));
		assertThat(requests).hasValue(2); // Two explicit caller operations, no automatic retries.
	}

	@ParameterizedTest
	@ValueSource(strings = {"{}", "[]", "null", "", "invalid json"})
	void rejectsMissingOrMalformedSuccess(String body) throws Exception {
		var client = start(200, "application/json", body, Duration.ofSeconds(2), 0);
		assertThatThrownBy(() -> client.cancel("process-1", null)).isInstanceOf(CatedraProtocolException.class).hasNoCause();
	}

	@ParameterizedTest
	@CsvSource(delimiter = '|', value = {
		"9223372036854775807|\"8251\"", "9223372036854775807|1.5", "9223372036854775807|9223372036854775808",
		"9223372036854775807|null", "process-1|different-process", "CANCELLED|CONFIRMED", "CANCELLED|' '",
		"2026-11-22T12:00:00Z|invalid", "\"cancelledAt\":\"2026-11-22T12:00:00Z\"|\"cancelledAt\":null",
		"\"reservationProcessId\":\"process-1\"|\"reservationProcessId\":123"})
	void rejectsInvalidOrUncorrelatedResponse(String from, String to) throws Exception {
		var client = start(200, "application/json", RESPONSE.replace(from, to), Duration.ofSeconds(2), 0);
		assertThatThrownBy(() -> client.cancel("process-1", PRIVATE_REASON)).isInstanceOf(CatedraProtocolException.class)
				.hasNoCause().hasMessageNotContaining(PRIVATE_REASON);
	}

	@ParameterizedTest
	@ValueSource(ints = {201,202,204,207})
	void requiresExactly200(int status) throws Exception {
		var client = start(status, "application/json", status == 204 ? "" : RESPONSE, Duration.ofSeconds(2), 0);
		assertThatThrownBy(() -> client.cancel("process-1", null)).isInstanceOf(CatedraProtocolException.class);
	}

	@ParameterizedTest
	@ValueSource(strings = {"text/plain", "application/problem+json", "invalid media type", "MISSING"})
	void requiresJson(String mediaType) throws Exception {
		var client = start(200, mediaType.equals("MISSING") ? null : mediaType, RESPONSE, Duration.ofSeconds(2), 0);
		assertThatThrownBy(() -> client.cancel("process-1", null)).isInstanceOf(CatedraProtocolException.class);
	}

	@ParameterizedTest
	@ValueSource(strings = {"reservationId", "reservationProcessId", "status", "cancelledAt"})
	void rejectsEachMissingRequiredField(String field) throws Exception {
		var mapper = new JsonMapper();
		var body = (tools.jackson.databind.node.ObjectNode) mapper.readTree(RESPONSE);
		body.remove(field);
		var client = start(200, "application/json", mapper.writeValueAsString(body), Duration.ofSeconds(2), 0);
		assertThatThrownBy(() -> client.cancel("process-1", null)).isInstanceOf(CatedraProtocolException.class).hasNoCause();
	}

	@Test
	void toleratesCompatibleAdditionalFields() throws Exception {
		var client = start(200, "application/json", RESPONSE.replace("\"status\":", "\"futureField\":{},\"status\":"), Duration.ofSeconds(2), 0);
		assertThat(client.cancel("process-1", null).status()).isEqualTo("CANCELLED");
	}

	@ParameterizedTest
	@CsvSource({"404,RESERVATION_NOT_FOUND,RESERVATION_NOT_FOUND", "403,RESERVATION_OWNER_MISMATCH,RESERVATION_OWNER_MISMATCH",
		"409,RESERVATION_NOT_CANCELLABLE,RESERVATION_NOT_CANCELLABLE", "403,STUDENT_INTEGRATION_NOT_FOUND,INTEGRATION_NOT_FOUND",
		"401,UNKNOWN,UNAUTHORIZED", "403,UNKNOWN,FORBIDDEN", "404,UNKNOWN,NOT_FOUND", "409,UNKNOWN,CONFLICT",
		"400,RESERVATION_NOT_FOUND,REQUEST_REJECTED", "422,UNKNOWN,REQUEST_REJECTED", "500,UNKNOWN,REMOTE_FAILURE", "503,UNKNOWN,REMOTE_FAILURE"})
	void mapsStatusAndCodeWithoutDetailOrRetries(int status, String code, String expected) throws Exception {
		var client = start(status, "application/problem+json", "{\"code\":\"" + code + "\",\"detail\":\"private diagnostic\"}", Duration.ofSeconds(2), 0);
		assertThatThrownBy(() -> new CatedraReservationCancellationAdapter(client).cancel("process-1", PRIVATE_REASON))
				.isInstanceOfSatisfying(ReservationCancellationException.class, failure -> assertThat(failure.reason().name()).isEqualTo(expected))
				.hasNoCause().hasMessageNotContaining("private diagnostic").hasMessageNotContaining(PRIVATE_REASON);
		assertThat(requests).hasValue(1);
	}

	@ParameterizedTest
	@ValueSource(ints = {401,403})
	void handlesSecurityErrorsWithoutCode(int status) throws Exception {
		var client = start(status, "text/plain", "private diagnostic", Duration.ofSeconds(2), 0);
		assertThatThrownBy(() -> new CatedraReservationCancellationAdapter(client).cancel("process-1", null))
				.isInstanceOfSatisfying(ReservationCancellationException.class,
						failure -> assertThat(failure.reason().name()).isEqualTo(status == 401 ? "UNAUTHORIZED" : "FORBIDDEN"))
				.hasNoCause();
		assertThat(requests).hasValue(1);
	}

	@Test
	void reportsTimeoutAsUncertainWithoutRepeatingThePost() throws Exception {
		var client = start(200, "application/json", RESPONSE, Duration.ofMillis(300), 1000);
		assertThatThrownBy(() -> new CatedraReservationCancellationAdapter(client).cancel("process-1", PRIVATE_REASON))
				.isInstanceOfSatisfying(ReservationCancellationException.class,
						failure -> assertThat(failure.reason()).isEqualTo(ReservationCancellationException.Reason.TIMEOUT))
				.hasNoCause();
		assertThat(requests).hasValue(1);
	}

	private CatedraReservationCancellationClient start(int status, String mediaType, String body, Duration timeout, long delay) throws Exception {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/", exchange -> {
			requests.incrementAndGet(); uri = exchange.getRequestURI(); method = exchange.getRequestMethod();
			authorization = exchange.getRequestHeaders().getFirst("Authorization");
			requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
			if (delay > 0) {
				try { Thread.sleep(delay); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
			}
			if (mediaType != null) { exchange.getResponseHeaders().set("Content-Type", mediaType); }
			byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
			try {
				exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
				try (var output = exchange.getResponseBody()) { output.write(bytes); }
			} catch (java.io.IOException disconnected) { exchange.close(); }
		});
		server.start();
		var properties = new CatedraIntegrationProperties("test-group", JWT,
				new CatedraIntegrationProperties.RestProperties("http://127.0.0.1:" + server.getAddress().getPort() + "/base", Duration.ofSeconds(2), timeout),
				new CatedraIntegrationProperties.KafkaProperties("example:9092", "group", "actions", "phone"));
		return new CatedraReservationCancellationClient(new CatedraRestClientConfiguration().catedraRestClient(properties, new JsonMapper()));
	}
}
