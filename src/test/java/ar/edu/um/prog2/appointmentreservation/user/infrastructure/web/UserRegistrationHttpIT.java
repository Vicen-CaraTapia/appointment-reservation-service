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
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.security.crypto.password.PasswordEncoder;

import tools.jackson.databind.json.JsonMapper;

import ar.edu.um.prog2.appointmentreservation.AppointmentReservationApplication;
import ar.edu.um.prog2.appointmentreservation.support.FinalUserJwtTestSupport;
import ar.edu.um.prog2.appointmentreservation.user.application.exception.DuplicateUserException.Field;
import ar.edu.um.prog2.appointmentreservation.user.infrastructure.persistence.entity.AuthorityEntity;
import ar.edu.um.prog2.appointmentreservation.user.infrastructure.persistence.repository.UserJpaRepository;

@ExtendWith(OutputCaptureExtension.class)
class UserRegistrationHttpIT {

	private static final String TEST_PASSWORD = "  test-only HTTP registration passphrase  ";
	private String url;
	private String username;
	private String password;
	private String schema;
	private ConfigurableApplicationContext context;
	private HttpClient client;
	private URI endpoint;
	private JsonMapper json;
	private UserJpaRepository users;

	@BeforeEach
	void startHttpServerWithAnIsolatedMigratedSchema() throws SQLException {
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
		Map<String, Object> properties = Map.of(
				FinalUserJwtTestSupport.SECRET_PROPERTY, FinalUserJwtTestSupport.signingSecret(),
				"spring.datasource.url", url,
				"spring.datasource.username", username,
				"spring.datasource.password", password,
				"spring.flyway.schemas", schema,
				"spring.flyway.default-schema", schema,
				"spring.flyway.create-schemas", false,
				"spring.jpa.properties.hibernate.default_schema", schema,
				"spring.jpa.hibernate.ddl-auto", "validate",
				"server.port", 0);
		context = new SpringApplicationBuilder(AppointmentReservationApplication.class)
				.web(WebApplicationType.SERVLET)
				.initializers(application -> application.getEnvironment().getPropertySources().addFirst(
						new MapPropertySource("userRegistrationHttpTest", properties)))
				.run();
		endpoint = URI.create("http://127.0.0.1:" + context.getEnvironment().getProperty("local.server.port")
				+ "/api/register");
		client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
		json = context.getBean(JsonMapper.class);
		users = context.getBean(UserJpaRepository.class);
	}

	@AfterEach
	void closeAndRemoveOnlyThisTestSchema() throws SQLException {
		try {
			if (client != null) {
				client.close();
			}
			if (context != null) {
				context.close();
			}
		}
		finally {
			if (schema != null) {
				try (Connection connection = connection(); var statement = connection.createStatement()) {
					statement.execute("DROP SCHEMA " + schema + " CASCADE");
				}
			}
		}
	}

	@Test
	void publicRegistrationPersistsAnActiveUserWithoutExposingAdministrativeData(CapturedOutput output)
			throws Exception {
		HttpResponse<String> response = post(validInput());
		assertThat(response.statusCode()).isEqualTo(201);
		var body = json.readTree(response.body());
		assertThat(body.size()).isEqualTo(6);
		assertThat(body.get("login").asString()).isEqualTo("alice");
		assertThat(body.get("firstName").asString()).isEqualTo("Ana");
		assertThat(body.get("lastName").asString()).isEqualTo("Perez");
		assertThat(body.get("email").asString()).isEqualTo("alice@example.test");
		assertThat(body.get("imageUrl").isNull()).isTrue();
		assertThat(body.get("langKey").asString()).isEqualTo("es");
		assertThat(response.body()).doesNotContain(TEST_PASSWORD, "password", "activated", "authorities",
				"createdBy", "createdDate", "lastModifiedBy", "lastModifiedDate", "\"id\"");
		var user = users.findByLoginIgnoreCase("ALICE").orElseThrow();
		assertThat(user.isActivated()).isTrue();
		assertThat(user.getAuthorities()).extracting(AuthorityEntity::getName).containsExactly("ROLE_USER");
		assertThat(user.getCreatedBy()).isEqualTo("alice");
		assertThat(user.getPasswordHash()).startsWith("$argon2id$v=19$m=19456,t=2,p=1$");
		assertThat(context.getBean(PasswordEncoder.class).matches(TEST_PASSWORD, user.getPasswordHash())).isTrue();
		assertThat(output.getAll()).doesNotContain(TEST_PASSWORD, user.getPasswordHash());
	}

	@ParameterizedTest
	@EnumSource(Field.class)
	void duplicateIdentityReturnsCompatibleStatusWithoutInsertingAnotherUser(Field field) throws Exception {
		assertThat(post(validInput()).statusCode()).isEqualTo(201);
		Map<String, Object> duplicate = validInput();
		duplicate.put("login", field == Field.LOGIN ? "ALICE" : "bob");
		duplicate.put("email", field == Field.EMAIL ? "ALICE@EXAMPLE.TEST" : "other@example.test");
		HttpResponse<String> response = post(duplicate);
		assertThat(response.statusCode()).isEqualTo(400);
		assertThat(json.readTree(response.body()).get("code").asString())
				.isEqualTo(field == Field.LOGIN ? "LOGIN_ALREADY_EXISTS" : "EMAIL_ALREADY_EXISTS");
		assertThat(users.count()).isEqualTo(1);
	}

	@Test
	void rejectsValidationErrorsAndAdministrativeFieldsWithoutCreatingUsers(CapturedOutput output)
			throws Exception {
		Map<String, Object> invalid = validInput();
		invalid.put("password", "abc");
		HttpResponse<String> validation = post(invalid);
		assertThat(validation.statusCode()).isEqualTo(400);
		assertThat(json.readTree(validation.body()).get("code").asString()).isEqualTo("VALIDATION_ERROR");
		for (String field : new String[] {"id", "activated", "authorities", "passwordHash", "createdBy"}) {
			Map<String, Object> attack = validInput();
			attack.put(field, "untrusted-administrative-input");
			HttpResponse<String> response = post(attack);
			assertThat(response.statusCode()).isEqualTo(400);
			assertThat(json.readTree(response.body()).get("code").asString()).isEqualTo("INVALID_REQUEST");
			assertThat(response.body()).doesNotContain(TEST_PASSWORD, "untrusted-administrative-input");
		}
		assertThat(users.count()).isZero();
		assertThat(output.getAll()).doesNotContain(TEST_PASSWORD, "untrusted-administrative-input");
	}

	@Test
	void internalFailureReturnsASafeErrorWithoutPartialRegistration(CapturedOutput output) throws Exception {
		try (Connection connection = connection(); var statement = connection.createStatement()) {
			statement.execute("DELETE FROM " + schema + ".authority WHERE name='ROLE_USER'");
		}
		HttpResponse<String> response = post(validInput());
		assertThat(response.statusCode()).isEqualTo(500);
		assertThat(json.readTree(response.body()).get("code").asString()).isEqualTo("REGISTRATION_FAILED");
		assertThat(response.body()).doesNotContain(TEST_PASSWORD, "Required registration authority", "Exception");
		assertThat(output.getAll()).doesNotContain(TEST_PASSWORD);
		assertThat(users.count()).isZero();
	}

	private HttpResponse<String> post(Map<String, Object> input) throws Exception {
		return client.send(HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(10))
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(input))).build(),
				HttpResponse.BodyHandlers.ofString());
	}

	private static Map<String, Object> validInput() {
		return new HashMap<>(Map.of("login", "Alice", "password", TEST_PASSWORD, "firstName", "Ana",
				"lastName", "Perez", "email", "ALICE@example.test"));
	}

	private Connection connection() throws SQLException {
		return DriverManager.getConnection(url, username, password);
	}

	private static String requiredEnvironment(String name) {
		String value = System.getenv(name);
		if (value == null || value.isBlank()) {
			throw new IllegalStateException("Set " + name + " for postgres-integration");
		}
		return value;
	}
}
