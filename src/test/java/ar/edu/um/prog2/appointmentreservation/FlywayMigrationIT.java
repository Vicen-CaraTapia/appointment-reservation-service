package ar.edu.um.prog2.appointmentreservation;

import ar.edu.um.prog2.appointmentreservation.support.CatedraIntegrationTestSupport;
import ar.edu.um.prog2.appointmentreservation.support.CatalogIntegrationTestSupport;
import ar.edu.um.prog2.appointmentreservation.support.FinalUserJwtTestSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.security.crypto.password.PasswordEncoder;

class FlywayMigrationIT {

	private String url;
	private String username;
	private String password;
	private String schema;

	@BeforeEach
	void createIsolatedSchema() throws SQLException {
		url = requiredEnvironment("APPOINTMENT_TEST_DB_URL");
		username = requiredEnvironment("APPOINTMENT_TEST_DB_USERNAME");
		password = requiredEnvironment("APPOINTMENT_TEST_DB_PASSWORD");
		try (Connection connection = connection(); var statement = connection.createStatement()) {
			try (var result = statement.executeQuery("SELECT current_database()")) {
				result.next();
				assertThat(result.getString(1))
						.as("Integration tests require an Appointment-owned database")
						.startsWith("appointment_");
			}
			String candidate = "appointment_it_" + UUID.randomUUID().toString().replace("-", "");
			statement.execute("CREATE SCHEMA " + candidate);
			schema = candidate;
		}
	}

	@AfterEach
	void removeOnlyThisTestSchema() throws SQLException {
		if (schema != null) {
			try (Connection connection = connection(); var statement = connection.createStatement()) {
				statement.execute("DROP SCHEMA " + schema + " CASCADE");
			}
		}
	}

	@Test
	void productionStartupAppliesTheUserModelAndDoesNotReapplyIt() throws SQLException {
		try (var context = start("classpath:db/migration")) {
			assertThat(context.getBean(Flyway.class).info().applied())
					.extracting(info -> info.getVersion().toString()).containsExactly("1");
			assertThat(query("SELECT count(*) FROM " + schema
					+ ".flyway_schema_history WHERE version='1' AND success AND type='SQL'"))
					.isEqualTo(1);
			assertThat(query("SELECT count(*) FROM information_schema.tables WHERE table_schema='"
					+ schema + "' AND table_name <> 'flyway_schema_history'"))
					.isEqualTo(3);
			assertThat(query("SELECT count(*) FROM " + schema + ".app_user")).isZero();
			assertThat(query("SELECT count(*) FROM " + schema
					+ ".authority WHERE name='ROLE_USER'")).isEqualTo(1);
			assertThat(query("SELECT character_maximum_length FROM information_schema.columns"
					+ " WHERE table_schema='" + schema
					+ "' AND table_name='app_user' AND column_name='password_hash'"))
					.isEqualTo(255);
		}
		try (var context = start("classpath:db/migration")) {
			assertThat(context.getBean(Flyway.class).migrate().migrationsExecuted).isZero();
			assertThat(query("SELECT count(*) FROM " + schema + ".authority")).isEqualTo(1);
		}
	}

	@Test
	void userSchemaStoresTheCompleteArgon2idHashAndNullableImage() throws SQLException {
		try (var context = start("classpath:db/migration")) {
			PasswordEncoder encoder = context.getBean(PasswordEncoder.class);
			String encoded = encoder.encode("test-only-schema-passphrase");
			long userId = insertUser("alice", "alice@example.test", encoded);
			try (Connection connection = connection(); var statement = connection.createStatement();
					var result = statement.executeQuery("SELECT password_hash, image_url, activated,"
						+ " created_by, created_date FROM " + schema + ".app_user WHERE id=" + userId)) {
				assertThat(result.next()).isTrue();
				assertThat(result.getString("password_hash")).isEqualTo(encoded);
				assertThat(encoder.matches("test-only-schema-passphrase", result.getString("password_hash")))
						.isTrue();
				assertThat(result.getString("image_url")).isNull();
				assertThat(result.getBoolean("activated")).isFalse();
				assertThat(result.getString("created_by")).isEqualTo("backend-test");
				assertThat(result.getTimestamp("created_date")).isNotNull();
			}
		}
	}

	@Test
	void schemaRejectsDuplicateLoginAndEmailIgnoringCase() throws SQLException {
		try (var context = start("classpath:db/migration")) {
			String encoded = context.getBean(PasswordEncoder.class).encode("test-only-schema-passphrase");
			insertUser("alice", "alice@example.test", encoded);
			assertThatThrownBy(() -> insertUser("ALICE", "other@example.test", encoded))
					.isInstanceOfSatisfying(SQLException.class,
							error -> assertThat(error.getSQLState()).isEqualTo("23505"));
			assertThatThrownBy(() -> insertUser("bob", "ALICE@example.test", encoded))
					.isInstanceOfSatisfying(SQLException.class,
							error -> assertThat(error.getSQLState()).isEqualTo("23505"));
		}
	}

	@Test
	void schemaProtectsAuthorityMembershipsAndCleansThemWithTheUser() throws SQLException {
		try (var context = start("classpath:db/migration")) {
			String encoded = context.getBean(PasswordEncoder.class).encode("test-only-schema-passphrase");
			long userId = insertUser("alice", "alice@example.test", encoded);
			try (Connection connection = connection(); var statement = connection.createStatement()) {
				statement.executeUpdate("INSERT INTO " + schema
						+ ".app_user_authority (user_id, authority_name) VALUES (" + userId + ", 'ROLE_USER')");
				assertThatThrownBy(() -> statement.executeUpdate("INSERT INTO " + schema
						+ ".app_user_authority (user_id, authority_name) VALUES (" + userId + ", 'ROLE_USER')"))
						.isInstanceOfSatisfying(SQLException.class,
								error -> assertThat(error.getSQLState()).isEqualTo("23505"));
				assertThatThrownBy(() -> statement.executeUpdate("INSERT INTO " + schema
						+ ".app_user_authority (user_id, authority_name) VALUES (" + userId + ", 'ROLE_UNKNOWN')"))
						.isInstanceOfSatisfying(SQLException.class,
								error -> assertThat(error.getSQLState()).isEqualTo("23503"));
				statement.executeUpdate("DELETE FROM " + schema + ".app_user WHERE id=" + userId);
			}
			assertThat(query("SELECT count(*) FROM " + schema + ".app_user_authority")).isZero();
			assertThat(query("SELECT count(*) FROM " + schema + ".authority")).isEqualTo(1);
		}
	}

	@Test
	void startupAppliesVersionsInOrderAndDoesNotReapplyThem() throws SQLException {
		try (var context = start("classpath:db/fixtures/ordered")) {
			assertThat(context.getBean(Flyway.class).info().applied())
					.extracting(info -> info.getVersion().toString()).containsExactly("1", "2");
			assertThat(query("SELECT count(*) FROM " + schema
					+ ".migration_probe WHERE id=1 AND description='v2'")).isEqualTo(1);
			assertThat(query("SELECT count(*) FROM " + schema
					+ ".flyway_schema_history WHERE success AND type='SQL'"
					+ " AND ((installed_rank=1 AND version='1')"
					+ " OR (installed_rank=2 AND version='2'))")).isEqualTo(2);
		}
		try (var context = start("classpath:db/fixtures/ordered")) {
			assertThat(context.getBean(Flyway.class).migrate().migrationsExecuted).isZero();
			assertThat(query("SELECT count(*) FROM " + schema + ".migration_probe")).isEqualTo(1);
			assertThat(query("SELECT count(*) FROM " + schema
					+ ".flyway_schema_history WHERE type='SQL'")).isEqualTo(2);
		}
	}

	@Test
	void invalidMigrationAbortsStartupAndRollsBackItsChanges() throws SQLException {
		assertThatThrownBy(() -> {
			try (var ignored = start("classpath:db/fixtures/invalid")) {
				// Successful startup would violate the assertion.
			}
		}).hasStackTraceContaining("V1__invalid_sql.sql")
				.hasStackTraceContaining("42601");
		assertThat(query("SELECT count(*) FROM information_schema.tables WHERE table_schema='"
				+ schema + "' AND table_name='migration_probe'")).isZero();
	}

	private ConfigurableApplicationContext start(String location) {
		Map<String, Object> properties = new HashMap<>();
		properties.put(FinalUserJwtTestSupport.SECRET_PROPERTY, FinalUserJwtTestSupport.signingSecret());
		properties.put("spring.datasource.url", url);
		properties.put("spring.datasource.username", username);
		properties.put("spring.datasource.password", password);
		properties.put("spring.flyway.schemas", schema);
		properties.put("spring.flyway.default-schema", schema);
		properties.put("spring.flyway.create-schemas", false);
		properties.put("spring.flyway.locations", location);
		properties.put("spring.jpa.properties.hibernate.default_schema", schema);
		// A high-priority source overrides application.yml without putting secrets in arguments.
		return new SpringApplicationBuilder(AppointmentReservationApplication.class)
				.initializers(CatedraIntegrationTestSupport::configure, CatalogIntegrationTestSupport::configure)
				.web(WebApplicationType.NONE)
				.initializers(context -> context.getEnvironment().getPropertySources().addFirst(
						new MapPropertySource("integrationDatabase", properties)))
				.run();
	}

	private int query(String sql) throws SQLException {
		try (Connection connection = connection(); var statement = connection.createStatement();
				var result = statement.executeQuery(sql)) {
			result.next();
			return result.getInt(1);
		}
	}

	private Connection connection() throws SQLException {
		return DriverManager.getConnection(url, username, password);
	}

	private long insertUser(String login, String email, String encodedPassword) throws SQLException {
		String sql = "INSERT INTO " + schema
				+ ".app_user (login, email, password_hash, lang_key, created_by)"
				+ " VALUES (?, ?, ?, 'es', 'backend-test') RETURNING id";
		try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement(sql)) {
			statement.setString(1, login);
			statement.setString(2, email);
			statement.setString(3, encodedPassword);
			try (var result = statement.executeQuery()) {
				result.next();
				return result.getLong(1);
			}
		}
	}

	private static String requiredEnvironment(String name) {
		String value = System.getenv(name);
		if (value == null || value.isBlank()) {
			throw new IllegalStateException("Set " + name + " for postgres-integration");
		}
		return value;
	}
}
