package ar.edu.um.prog2.appointmentreservation.user.infrastructure.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import tools.jackson.databind.json.JsonMapper;

import ar.edu.um.prog2.appointmentreservation.AppointmentReservationApplication;
import ar.edu.um.prog2.appointmentreservation.support.CatedraIntegrationTestSupport;
import ar.edu.um.prog2.appointmentreservation.support.CatalogIntegrationTestSupport;
import ar.edu.um.prog2.appointmentreservation.support.FinalUserJwtTestSupport;
import ar.edu.um.prog2.appointmentreservation.user.infrastructure.persistence.entity.UserEntity;
import ar.edu.um.prog2.appointmentreservation.user.infrastructure.persistence.repository.UserJpaRepository;
import ar.edu.um.prog2.appointmentreservation.user.infrastructure.security.FinalUserJwtProperties;

@ExtendWith(OutputCaptureExtension.class)
class FinalUserAuthenticationHttpIT {
	private static final String PASSWORD = "  Test-only authentication passphrase  ";
	private String url;
	private String username;
	private String password;
	private String schema;
	private ConfigurableApplicationContext context;
	private HttpClient client;
	private String baseUrl;
	private JsonMapper json;
	private UserJpaRepository users;

	@BeforeEach
	void startInAnIsolatedMigratedAppointmentSchema() throws SQLException {
		url = requiredEnvironment("APPOINTMENT_TEST_DB_URL");
		username = requiredEnvironment("APPOINTMENT_TEST_DB_USERNAME");
		password = requiredEnvironment("APPOINTMENT_TEST_DB_PASSWORD");
		try (Connection connection = connection(); var statement = connection.createStatement()) {
			try (var result = statement.executeQuery("SELECT current_database()")) {
				result.next();
				assertThat(result.getString(1)).startsWith("appointment_");
			}
			String candidate = "appointment_it_" + UUID.randomUUID().toString().replace("-", "");
			statement.execute("CREATE SCHEMA " + candidate);
			schema = candidate;
		}
		Map<String, Object> properties = new HashMap<>();
		properties.put(FinalUserJwtTestSupport.SECRET_PROPERTY, FinalUserJwtTestSupport.signingSecret());
		properties.put("appointment.security.final-user-jwt.ttl", "1h");
		properties.put("appointment.security.final-user-jwt.remember-me-ttl", "7d");
		properties.put("spring.datasource.url", url);
		properties.put("spring.datasource.username", username);
		properties.put("spring.datasource.password", password);
		properties.put("spring.flyway.schemas", schema);
		properties.put("spring.flyway.default-schema", schema);
		properties.put("spring.flyway.create-schemas", false);
		properties.put("spring.jpa.properties.hibernate.default_schema", schema);
		properties.put("spring.jpa.hibernate.ddl-auto", "validate");
		properties.put("server.port", 0);
		context = new SpringApplicationBuilder(AppointmentReservationApplication.class)
				.initializers(CatedraIntegrationTestSupport::configure, CatalogIntegrationTestSupport::configure)
				.web(WebApplicationType.SERVLET)
				.initializers(application -> application.getEnvironment().getPropertySources().addFirst(
						new MapPropertySource("finalUserAuthenticationTest", properties))).run();
		baseUrl = "http://127.0.0.1:" + context.getEnvironment().getProperty("local.server.port");
		client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
		json = context.getBean(JsonMapper.class);
		users = context.getBean(UserJpaRepository.class);
	}

	@AfterEach
	void closeAndRemoveOnlyThisTestSchema() throws SQLException {
		try {
			if (client != null) { client.close(); }
			if (context != null) { context.close(); }
		}
		finally {
			if (schema != null) {
				try (Connection connection = connection(); var statement = connection.createStatement()) {
					statement.execute("DROP SCHEMA " + schema + " CASCADE");
				}
			}
		}
	}

	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	void registrationThenCaseInsensitiveLoginProducesAnHs512Jwt(boolean rememberMe, CapturedOutput output) throws Exception {
		register();
		Instant before = Instant.now().minusSeconds(1);
		HttpResponse<String> response = login("ALICE", PASSWORD, rememberMe);
		assertThat(response.statusCode()).isEqualTo(200);
		var body = json.readTree(response.body());
		assertThat(body.size()).isEqualTo(1);
		String token = body.get("id_token").asString();
		assertThat(response.headers().firstValue("Authorization")).contains("Bearer " + token);
		assertThat(response.headers().firstValue("Set-Cookie")).isEmpty();
		assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
		SecretKey key = new SecretKeySpec(Base64.getDecoder().decode(FinalUserJwtTestSupport.signingSecret()), "HmacSHA512");
		var jwt = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS512).build().decode(token);
		assertThat(jwt.getHeaders()).containsEntry("alg", "HS512");
		assertThat(jwt.getClaims()).containsOnlyKeys("sub", "userId", "auth", "iat", "exp", "iss", "aud");
		assertThat(jwt.getSubject()).isEqualTo("alice");
		var user = users.findByLoginIgnoreCase("alice").orElseThrow();
		assertThat(((Number) jwt.getClaim("userId")).longValue()).isEqualTo(user.getId());
		assertThat(jwt.getClaimAsString("auth")).isEqualTo("ROLE_USER");
		assertThat(jwt.getClaimAsString("iss")).isEqualTo(FinalUserJwtProperties.ISSUER);
		assertThat(jwt.getAudience()).containsExactly(FinalUserJwtProperties.AUDIENCE);
		assertThat(jwt.getIssuedAt()).isBetween(before, Instant.now());
		assertThat(Duration.between(jwt.getIssuedAt(), jwt.getExpiresAt()))
				.isEqualTo(rememberMe ? Duration.ofDays(7) : Duration.ofHours(1));
		assertThat(context.getBean(PasswordEncoder.class).matches(PASSWORD, user.getPasswordHash())).isTrue();
		assertThat(jwt.getClaims().toString()).doesNotContain(PASSWORD, user.getPasswordHash(), FinalUserJwtTestSupport.signingSecret());
		assertThat(output.getAll()).doesNotContain(PASSWORD, token, user.getPasswordHash(), FinalUserJwtTestSupport.signingSecret());
		var health = client.send(HttpRequest.newBuilder(URI.create(baseUrl + "/actuator/health")).GET().build(),
				HttpResponse.BodyHandlers.ofString());
		assertThat(health.statusCode()).isEqualTo(200);
		var closed = client.send(HttpRequest.newBuilder(URI.create(baseUrl + "/api/private"))
				.header("Authorization", "Bearer " + token).GET().build(), HttpResponse.BodyHandlers.ofString());
		// The valid Bearer token reaches MVC; this nonexistent endpoint returns 404.
		assertThat(closed.statusCode()).isEqualTo(404);
	}

	@Test
	void invalidPasswordUnknownInactiveAndEmailIdentityHaveIdentical401Responses(CapturedOutput output) throws Exception {
		register();
		String hash = context.getBean(PasswordEncoder.class).encode(PASSWORD);
		users.saveAndFlush(new UserEntity("inactive", hash, null, null, "inactive@example.test", null, "es", "backend-test"));
		String expected = null;
		for (Map<String, String> input : java.util.List.of(
				Map.of("username", "alice", "password", "incorrect-test-password"),
				Map.of("username", "unknown-user", "password", PASSWORD),
				Map.of("username", "inactive", "password", PASSWORD),
				Map.of("username", "alice@example.test", "password", PASSWORD),
				Map.of("username", "alice", "password", PASSWORD.strip()))) {
			HttpResponse<String> response = login(input.get("username"), input.get("password"), false);
			assertThat(response.statusCode()).isEqualTo(401);
			assertThat(response.headers().firstValue("Authorization")).isEmpty();
			assertThat(json.readTree(response.body()).get("code").asString()).isEqualTo("INVALID_CREDENTIALS");
			if (expected == null) { expected = response.body(); }
			assertThat(response.body()).isEqualTo(expected).doesNotContain(PASSWORD, hash, "inactive", "unknown-user");
		}
		assertThat(output.getAll()).doesNotContain(PASSWORD, hash);
	}

	@Test
	void invalidFieldsAndClientChosenClaimsAreRejectedBeforeAuthentication() throws Exception {
		HttpResponse<String> invalid = login("alice", "abc", false);
		assertThat(invalid.statusCode()).isEqualTo(400);
		assertThat(json.readTree(invalid.body()).get("code").asString()).isEqualTo("VALIDATION_ERROR");
		HttpResponse<String> attack = post("/api/authenticate", Map.of("username", "alice", "password", PASSWORD,
				"authorities", java.util.List.of("ROLE_ADMIN"), "userId", 42));
		assertThat(attack.statusCode()).isEqualTo(400);
		assertThat(json.readTree(attack.body()).get("code").asString()).isEqualTo("INVALID_REQUEST");
		assertThat(users.count()).isZero();
	}

	@Test
	void missingStorageIsReportedAsUnavailableInsteadOfInvalidCredentials() throws Exception {
		// Fault injection changes only this test's disposable schema.
		try (Connection connection = connection(); var statement = connection.createStatement()) {
			statement.execute("ALTER TABLE " + schema + ".app_user RENAME TO temporarily_unavailable_app_user");
		}
		HttpResponse<String> response = login("alice", PASSWORD, false);
		assertThat(response.statusCode()).isEqualTo(503);
		assertThat(json.readTree(response.body()).get("code").asString()).isEqualTo("AUTHENTICATION_UNAVAILABLE");
		assertThat(response.body()).doesNotContain(PASSWORD, "app_user", "SQL", "Exception");
		assertThat(response.headers().firstValue("Authorization")).isEmpty();
	}

	private void register() throws Exception {
		assertThat(post("/api/register", Map.of("login", "Alice", "password", PASSWORD, "email", "alice@example.test"))
				.statusCode()).isEqualTo(201);
	}

	private HttpResponse<String> login(String login, String password, boolean rememberMe) throws Exception {
		return post("/api/authenticate", Map.of("username", login, "password", password, "rememberMe", rememberMe));
	}

	private HttpResponse<String> post(String path, Map<String, Object> input) throws Exception {
		return client.send(HttpRequest.newBuilder(URI.create(baseUrl + path)).timeout(Duration.ofSeconds(10))
				.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(input)))
				.build(), HttpResponse.BodyHandlers.ofString());
	}

	private Connection connection() throws SQLException { return DriverManager.getConnection(url, username, password); }

	private static String requiredEnvironment(String name) {
		String value = System.getenv(name);
		if (value == null || value.isBlank()) { throw new IllegalStateException("Set " + name + " for postgres-integration"); }
		return value;
	}
}
