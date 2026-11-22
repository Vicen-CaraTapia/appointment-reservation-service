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
	void productionStartupAppliesTheUserAndProcessModelsWithoutReapplyingThem() throws SQLException {
		try (var context = start("classpath:db/migration")) {
			assertThat(context.getBean(Flyway.class).info().applied())
					.extracting(info -> info.getVersion().toString()).containsExactly("1", "2", "3", "4", "5", "6", "7", "8", "9");
			assertThat(query("SELECT count(*) FROM " + schema
					+ ".flyway_schema_history WHERE version='1' AND success AND type='SQL'"))
					.isEqualTo(1);
			assertThat(query("SELECT count(*) FROM information_schema.tables WHERE table_schema='"
					+ schema + "' AND table_name <> 'flyway_schema_history'"))
					.isEqualTo(7);
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
	void v9UpgradesExistingV8ReservationsWithoutInventingRestEvidence() throws SQLException {
		var before=Flyway.configure().dataSource(url,username,password).schemas(schema).defaultSchema(schema)
				.createSchemas(false).locations("classpath:db/migration").target("8").load();
		before.migrate();
		long userId=insertUser("rest-upgrade","rest-upgrade@example.test","synthetic-hash");
		try(var connection=connection();var statement=connection.createStatement()) {
			statement.executeUpdate("INSERT INTO "+schema+".reservation_process (owner_user_id,professional_id,appointment_date,start_time,"
					+"external_patient_id,created_by,process_status,hold_id,external_reservation_process_id,expires_at,"
					+"patient_first_name,patient_last_name,request_event_id,external_reservation_id) VALUES ("+userId
					+",202,DATE '2026-12-01',TIME '12:00','user-"+userId+"','test','CONFIRMED','hold-old','process-old',"
					+"CURRENT_TIMESTAMP,'Test','Patient','request-old',8251)");
		}
		var upgraded=Flyway.configure().dataSource(url,username,password).schemas(schema).defaultSchema(schema)
				.createSchemas(false).locations("classpath:db/migration").load();
		assertThat(upgraded.migrate().migrationsExecuted).isEqualTo(1);
		assertThat(query("SELECT count(*) FROM "+schema+".reservation_process WHERE process_status='CONFIRMED' "
				+"AND request_event_id='request-old' AND external_reservation_id=8251 AND rest_verified_at IS NULL AND rest_observed_status IS NULL")).isEqualTo(1);
		assertThat(upgraded.migrate().migrationsExecuted).isZero();
	}

	@Test
	void v3UpgradesExistingPendingAndHeldRowsWithoutChangingTheirIdentityOrExpiry() throws SQLException {
		var flywayV2 = Flyway.configure().dataSource(url, username, password).schemas(schema).defaultSchema(schema)
				.createSchemas(false).locations("classpath:db/migration").target("2").load();
		flywayV2.migrate();
		long userId = insertUser("upgrade-user", "upgrade@example.test", "synthetic-test-hash");
		try (var connection = connection(); var statement = connection.createStatement()) {
			statement.executeUpdate("INSERT INTO " + schema + ".reservation_process"
					+ " (owner_user_id,professional_id,appointment_date,start_time,external_patient_id,created_by)"
					+ " VALUES (" + userId + ",202,DATE '2026-11-19',TIME '18:00','user-" + userId + "','actor')");
			statement.executeUpdate("INSERT INTO " + schema + ".reservation_process"
					+ " (owner_user_id,professional_id,appointment_date,start_time,external_patient_id,created_by,"
					+ "process_status,hold_id,external_reservation_process_id,expires_at)"
					+ " VALUES (" + userId + ",202,DATE '2026-11-19',TIME '18:30','user-" + userId
					+ "','actor','HELD','hold-upgrade','process-upgrade',TIMESTAMPTZ '2026-11-19 15:00:00.123456Z')");
		}
		var flyway = Flyway.configure().dataSource(url, username, password).schemas(schema).defaultSchema(schema)
				.createSchemas(false).locations("classpath:db/migration").target("3").load();
		assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);
		assertThat(query("SELECT count(*) FROM " + schema + ".reservation_process WHERE version=0"
				+ " AND patient_first_name IS NULL AND patient_last_name IS NULL AND owner_user_id=" + userId))
				.isEqualTo(2);
		assertThat(query("SELECT count(*) FROM " + schema + ".reservation_process WHERE process_status='HELD'"
				+ " AND hold_id='hold-upgrade' AND external_reservation_process_id='process-upgrade'"
				+ " AND expires_at=TIMESTAMPTZ '2026-11-19 15:00:00.123456Z'"))
				.isEqualTo(1);
		assertThat(flyway.migrate().migrationsExecuted).isZero();
	}

	@Test
	void v4PreservesExistingV3PendingHeldAndWaitingRowsAndTheirVersions() throws SQLException {
		Flyway.configure().dataSource(url, username, password).schemas(schema).defaultSchema(schema)
				.createSchemas(false).locations("classpath:db/migration").target("3").load().migrate();
		long userId = insertUser("upgrade-v4", "upgrade-v4@example.test", "synthetic-test-hash");
		try (var connection = connection(); var statement = connection.createStatement()) {
			statement.executeUpdate("INSERT INTO " + schema + ".reservation_process"
					+ " (owner_user_id,professional_id,appointment_date,start_time,external_patient_id,created_by,"
					+ "process_status,hold_id,external_reservation_process_id,expires_at,patient_first_name,patient_last_name,version)"
					+ " SELECT " + userId + ",202,DATE '2026-11-19',TIME '18:30','user-" + userId + "','actor',"
					+ " state,'hold-v4-' || n,'process-v4-' || n,TIMESTAMPTZ '2026-11-19 15:00:00Z',first_name,last_name,n"
					+ " FROM (VALUES (1,'PENDING_HOLD',NULL,NULL),(2,'HELD',NULL,NULL),"
					+ " (3,'WAITING_FOR_PHONE','Ana','Perez')) AS previous(n,state,first_name,last_name)");
		}
		var flyway = Flyway.configure().dataSource(url, username, password).schemas(schema).defaultSchema(schema)
				.createSchemas(false).locations("classpath:db/migration").target("4").load();
		assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);
		assertThat(query("SELECT count(*) FROM " + schema + ".reservation_process WHERE owner_user_id=" + userId
				+ " AND hold_id='hold-v4-' || version AND external_reservation_process_id='process-v4-' || version"
				+ " AND expires_at=TIMESTAMPTZ '2026-11-19 15:00:00Z' AND created_by='actor'"
				+ " AND ((version=1 AND process_status='PENDING_HOLD' AND patient_first_name IS NULL)"
				+ " OR (version=2 AND process_status='HELD' AND patient_first_name IS NULL)"
				+ " OR (version=3 AND process_status='WAITING_FOR_PHONE' AND patient_first_name='Ana' AND patient_last_name='Perez'))"))
				.isEqualTo(3);
		assertThat(flyway.migrate().migrationsExecuted).isZero();
	}

	@Test
	void v6PreservesExistingV5RequestEvidenceWithoutInventingPhoneIntentions() throws SQLException {
		Flyway.configure().dataSource(url, username, password).schemas(schema).defaultSchema(schema)
				.createSchemas(false).locations("classpath:db/migration").target("5").load().migrate();
		long owner = insertUser("upgrade-v6", "upgrade-v6@example.test", "synthetic-test-hash");
		try (var connection = connection(); var statement = connection.createStatement()) {
			statement.executeUpdate("INSERT INTO " + schema + ".reservation_process"
					+ " (owner_user_id,professional_id,appointment_date,start_time,external_patient_id,created_by,process_status,"
					+ "hold_id,external_reservation_process_id,expires_at,patient_first_name,patient_last_name,request_event_id,version)"
					+ " VALUES (" + owner + ",202,DATE '2030-01-03',TIME '18:00','user-" + owner
					+ "','actor','WAITING_FOR_PHONE','hold-v6','process-v6',TIMESTAMPTZ '2026-11-20 12:05:00Z','Ana','Perez','request-v6',7)");
			statement.executeUpdate("INSERT INTO " + schema + ".reservation_event_inbox"
					+ " (event_id,external_process_id,payload,fingerprint,handling_status,local_process_id,handled_date)"
					+ " SELECT 'request-v6','process-v6','{}','" + "a".repeat(64)
					+ "','APPLIED',id,CURRENT_TIMESTAMP FROM " + schema + ".reservation_process WHERE request_event_id='request-v6'");
		}
		var flyway = Flyway.configure().dataSource(url, username, password).schemas(schema).defaultSchema(schema)
				.createSchemas(false).locations("classpath:db/migration").target("6").load();
		assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);
		assertThat(query("SELECT count(*) FROM " + schema + ".reservation_process WHERE owner_user_id=" + owner
				+ " AND version=7 AND request_event_id='request-v6' AND process_status='WAITING_FOR_PHONE'"
				+ " AND current_phone_submission_event_id IS NULL AND patient_first_name='Ana' AND patient_last_name='Perez'"
				+ " AND expires_at=TIMESTAMPTZ '2026-11-20 12:05:00Z' AND hold_id='hold-v6' AND external_reservation_process_id='process-v6'"))
				.isEqualTo(1);
		assertThat(query("SELECT count(*) FROM " + schema + ".reservation_event_inbox WHERE event_id='request-v6' AND handling_status='APPLIED'"))
				.isEqualTo(1);
		assertThat(query("SELECT count(*) FROM " + schema + ".reservation_phone_submission")).isZero();
		assertThat(flyway.migrate().migrationsExecuted).isZero();
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
