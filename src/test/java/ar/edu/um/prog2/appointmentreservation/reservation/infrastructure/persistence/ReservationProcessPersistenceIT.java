package ar.edu.um.prog2.appointmentreservation.reservation.infrastructure.persistence;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import ar.edu.um.prog2.appointmentreservation.AppointmentReservationApplication;
import ar.edu.um.prog2.appointmentreservation.reservation.application.ExternalPatientId;
import ar.edu.um.prog2.appointmentreservation.reservation.domain.model.ReservationProcessStatus;
import ar.edu.um.prog2.appointmentreservation.reservation.infrastructure.persistence.entity.ReservationProcessEntity;
import ar.edu.um.prog2.appointmentreservation.reservation.infrastructure.persistence.repository.ReservationProcessJpaRepository;
import ar.edu.um.prog2.appointmentreservation.support.CatedraIntegrationTestSupport;
import ar.edu.um.prog2.appointmentreservation.support.CatalogIntegrationTestSupport;
import ar.edu.um.prog2.appointmentreservation.support.FinalUserJwtTestSupport;
import ar.edu.um.prog2.appointmentreservation.user.infrastructure.persistence.entity.UserEntity;
import ar.edu.um.prog2.appointmentreservation.user.infrastructure.persistence.repository.UserJpaRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReservationProcessPersistenceIT {

	private static final LocalDate DATE = LocalDate.of(2026, 11, 17);
	private static final LocalTime TIME = LocalTime.of(11, 30, 0, 123456000);
	private static final Instant EXPIRY = Instant.parse("2026-11-17T14:40:00.123456Z");
	private String url;
	private String username;
	private String password;
	private String schema;
	private ConfigurableApplicationContext context;
	private UserJpaRepository users;
	private ReservationProcessJpaRepository processes;
	private UserEntity owner;
	private TransactionTemplate transactions;

	@BeforeEach
	void upgradeAnIsolatedV1SchemaAndValidateTheJpaMappings() throws SQLException {
		url = requiredEnvironment("APPOINTMENT_TEST_DB_URL");
		username = requiredEnvironment("APPOINTMENT_TEST_DB_USERNAME");
		password = requiredEnvironment("APPOINTMENT_TEST_DB_PASSWORD");
		try (var connection = connection(); var statement = connection.createStatement()) {
			try (var result = statement.executeQuery("SELECT current_database()")) {
				result.next();
				assertThat(result.getString(1)).startsWith("appointment_");
			}
			String candidate = "appointment_process_it_" + UUID.randomUUID().toString().replace("-", "");
			statement.execute("CREATE SCHEMA " + candidate);
			schema = candidate;
		}
		Flyway.configure().dataSource(url, username, password).schemas(schema).defaultSchema(schema)
				.createSchemas(false).locations("classpath:db/migration").target("1").load().migrate();
		Map<String, Object> properties = Map.of(
				FinalUserJwtTestSupport.SECRET_PROPERTY, FinalUserJwtTestSupport.signingSecret(),
				"spring.datasource.url", url,
				"spring.datasource.username", username,
				"spring.datasource.password", password,
				"spring.flyway.schemas", schema,
				"spring.flyway.default-schema", schema,
				"spring.flyway.create-schemas", false,
				"spring.jpa.properties.hibernate.default_schema", schema,
				"spring.jpa.hibernate.ddl-auto", "validate");
		context = new SpringApplicationBuilder(AppointmentReservationApplication.class)
				.initializers(CatedraIntegrationTestSupport::configure, CatalogIntegrationTestSupport::configure)
				.web(WebApplicationType.NONE)
				.initializers(application -> application.getEnvironment().getPropertySources().addFirst(
						new MapPropertySource("reservationProcessTest", properties)))
				.run();
		users = context.getBean(UserJpaRepository.class);
		processes = context.getBean(ReservationProcessJpaRepository.class);
		transactions = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
		String hash = context.getBean(PasswordEncoder.class).encode("test-only-process-persistence-passphrase");
		owner = users.saveAndFlush(new UserEntity("alice", hash, null, null, "alice@example.test", null, "es", "backend-test"));
	}

	@AfterEach
	void closeAndRemoveOnlyThisTestSchema() throws SQLException {
		try {
			if (context != null) {
				context.close();
			}
		} finally {
			if (schema != null) {
				try (var connection = connection(); var statement = connection.createStatement()) {
					statement.execute("DROP SCHEMA " + schema + " CASCADE");
				}
			}
		}
	}

	@Test
	void appliesV2AfterAnExistingV1AndDoesNotReapplyEitherMigration() {
		var flyway = context.getBean(Flyway.class);
		assertThat(flyway.info().applied()).extracting(info -> info.getVersion().toString()).containsExactly("1", "2");
		assertThat(flyway.migrate().migrationsExecuted).isZero();
	}

	@Test
	void storesAndReloadsAnOwnedPendingProcessWithCreationAuditAndNoExternalIds() {
		var saved = processes.saveAndFlush(pending(owner));
		var loaded = processes.findByIdAndOwner_Id(saved.getId(), owner.getId()).orElseThrow();
		assertThat(loaded.getId()).isPositive().isEqualTo(saved.getId());
		assertThat(loaded.getOwner().getId()).isEqualTo(owner.getId());
		assertThat(loaded.getProfessionalId()).isEqualTo(Long.MAX_VALUE);
		assertThat(loaded.getAppointmentDate()).isEqualTo(DATE);
		assertThat(loaded.getStartTime()).isEqualTo(TIME);
		assertThat(loaded.getExternalPatientId()).isEqualTo("user-" + owner.getId());
		assertThat(loaded.getStatus()).isEqualTo(ReservationProcessStatus.PENDING_HOLD);
		assertThat(loaded.getHoldId()).isNull();
		assertThat(loaded.getExternalReservationProcessId()).isNull();
		assertThat(loaded.getExpiresAt()).isNull();
		assertThat(loaded.getCreatedBy()).isEqualTo("backend-test");
		assertThat(loaded.getCreatedDate()).isNotNull();
		assertThat(loaded.getLastModifiedBy()).isNull();
		assertThat(loaded.getLastModifiedDate()).isNull();
	}

	@Test
	void attachesTheHoldAndKeepsLocalAndExternalIdsAndAuditingSeparate() {
		var saved = processes.saveAndFlush(pending(owner));
		Instant created = processes.findById(saved.getId()).orElseThrow().getCreatedDate();
		String holdId = "hold-" + "x".repeat(300);
		String externalProcessId = "process-" + "y".repeat(300);
		transactions.executeWithoutResult(status -> {
			var process = processes.findByIdAndOwner_Id(saved.getId(), owner.getId()).orElseThrow();
			process.markHeld(holdId, externalProcessId, EXPIRY, "hold-adapter");
			processes.flush();
		});
		var loaded = processes.findById(saved.getId()).orElseThrow();
		assertThat(loaded.getStatus()).isEqualTo(ReservationProcessStatus.HELD);
		assertThat(loaded.getHoldId()).isEqualTo(holdId);
		assertThat(loaded.getExternalReservationProcessId()).isEqualTo(externalProcessId);
		assertThat(loaded.getId().toString()).isNotEqualTo(externalProcessId);
		assertThat(loaded.getExpiresAt()).isEqualTo(EXPIRY);
		assertThat(loaded.getCreatedBy()).isEqualTo("backend-test");
		assertThat(loaded.getCreatedDate()).isEqualTo(created);
		assertThat(loaded.getLastModifiedBy()).isEqualTo("hold-adapter");
		assertThat(loaded.getLastModifiedDate()).isAfterOrEqualTo(created);
	}

	@Test
	void rejectsAnOwnerThatDoesNotExistThroughTheDatabaseForeignKey() {
		var absentOwner = users.getReferenceById(Long.MAX_VALUE);
		var process = pending(absentOwner);
		assertThatThrownBy(() -> processes.saveAndFlush(process)).isInstanceOf(DataIntegrityViolationException.class);
		assertThat(processes.count()).isZero();
	}

	@Test
	void ownershipScopedLookupDoesNotReturnAnotherUsersProcess() {
		var saved = processes.saveAndFlush(pending(owner));
		var other = users.saveAndFlush(new UserEntity("bob", owner.getPasswordHash(), null, null,
				"bob@example.test", null, "es", "backend-test"));
		assertThat(processes.findByIdAndOwner_Id(saved.getId(), other.getId())).isEmpty();
		assertThat(processes.findByIdAndOwner_Id(saved.getId(), owner.getId())).isPresent();
	}

	@Test
	void keepsAStablePatientIdAfterProfileChangesAndAllowsMultipleProcessesAtTheSamePosition() {
		var first = processes.saveAndFlush(pending(owner));
		owner.updateProfile("Renamed", "Example", "changed@example.test", null, "es", "profile-editor");
		owner = users.saveAndFlush(owner);
		var second = processes.saveAndFlush(pending(owner));
		assertThat(first.getExternalPatientId()).isEqualTo(second.getExternalPatientId());
		assertThat(first.getId()).isNotEqualTo(second.getId());
		assertThat(processes.count()).isEqualTo(2);
	}

	@ParameterizedTest
	@ValueSource(strings = {"hold", "external-process"})
	void rejectsDuplicateExternalIdentifiersWhenPresent(String duplicateField) {
		var first = pending(owner);
		first.markHeld("hold-first", "process-first", EXPIRY, "backend-test");
		processes.saveAndFlush(first);
		var duplicate = pending(owner);
		duplicate.markHeld(duplicateField.equals("hold") ? "hold-first" : "hold-other",
				duplicateField.equals("external-process") ? "process-first" : "process-other", EXPIRY, "backend-test");
		assertThatThrownBy(() -> processes.saveAndFlush(duplicate)).isInstanceOf(DataIntegrityViolationException.class);
		assertThat(processes.count()).isEqualTo(1);
	}

	@Test
	void doesNotUseAUniquePositionConstraintAsASubstituteForCatedraConcurrencyControl() {
		var first = pending(owner);
		first.markHeld("hold-one", "process-one", EXPIRY, "backend-test");
		var second = pending(owner);
		second.markHeld("hold-two", "process-two", EXPIRY, "backend-test");
		processes.saveAndFlush(first);
		processes.saveAndFlush(second);
		assertThat(processes.count()).isEqualTo(2);
	}

	@Test
	void heldRequiresBothExternalIdsAndTheAuthoritativeExpirationEvenForDirectSql() throws SQLException {
		long id = processes.saveAndFlush(pending(owner)).getId();
		for (String fields : List.of(
				"hold_id=NULL, external_reservation_process_id='process', expires_at=CURRENT_TIMESTAMP",
				"hold_id='hold', external_reservation_process_id=NULL, expires_at=CURRENT_TIMESTAMP",
				"hold_id='hold', external_reservation_process_id='process', expires_at=NULL")) {
			String sql = "UPDATE reservation_process SET process_status='HELD', " + fields + " WHERE id=" + id;
			assertSqlRejected(sql, "23514");
		}
		assertThat(processes.findById(id).orElseThrow().getStatus()).isEqualTo(ReservationProcessStatus.PENDING_HOLD);
	}

	@Test
	void databaseEnforcesRequiredFieldsIdentityAndAuditConsistency() throws SQLException {
		long id = processes.saveAndFlush(pending(owner)).getId();
		for (String field : List.of("owner_user_id", "professional_id", "appointment_date", "start_time", "external_patient_id", "process_status")) {
			assertSqlRejected("UPDATE reservation_process SET " + field + "=NULL WHERE id=" + id, "23502");
		}
		for (String mutation : List.of("external_patient_id='user-0'", "hold_id=' '",
				"external_reservation_process_id=' '", "process_status='FAILED'", "created_by=' '",
				"last_modified_by='actor'", "last_modified_date=CURRENT_TIMESTAMP")) {
			assertSqlRejected("UPDATE reservation_process SET " + mutation + " WHERE id=" + id, "23514");
		}
	}

	@Test
	void hasOnlyAnAppointmentOwnedForeignKeyAndProtectsOwnerDeletion() throws SQLException {
		processes.saveAndFlush(pending(owner));
		try (var connection = connection(); var keys = connection.getMetaData().getImportedKeys(null, schema, "reservation_process")) {
			assertThat(keys.next()).isTrue();
			assertThat(keys.getString("PKTABLE_SCHEM")).isEqualTo(schema);
			assertThat(keys.getString("PKTABLE_NAME")).isEqualTo("app_user");
			assertThat(keys.getString("FKCOLUMN_NAME")).isEqualTo("owner_user_id");
			assertThat(keys.next()).isFalse();
		}
		assertSqlRejected("DELETE FROM app_user WHERE id=" + owner.getId(), "23503");
	}

	@Test
	void rollsBackAConflictingHoldAttachmentWithoutChangingThePendingProcess() {
		var held = pending(owner);
		held.markHeld("hold-used", "process-used", EXPIRY, "backend-test");
		processes.saveAndFlush(held);
		var pending = processes.saveAndFlush(pending(owner));
		assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
			var process = processes.findById(pending.getId()).orElseThrow();
			process.markHeld("hold-used", "process-new", EXPIRY, "backend-test");
			processes.flush();
		})).isInstanceOf(DataIntegrityViolationException.class);
		var loaded = processes.findById(pending.getId()).orElseThrow();
		assertThat(loaded.getStatus()).isEqualTo(ReservationProcessStatus.PENDING_HOLD);
		assertThat(loaded.getHoldId()).isNull();
		assertThat(loaded.getLastModifiedDate()).isNull();
	}

	private static ReservationProcessEntity pending(UserEntity user) {
		return new ReservationProcessEntity(user, Long.MAX_VALUE, DATE, TIME,
				ExternalPatientId.fromUserId(user.getId()), "backend-test");
	}

	private void assertSqlRejected(String sql, String sqlState) throws SQLException {
		try (var connection = connection(); var statement = connection.createStatement()) {
			assertThatThrownBy(() -> statement.executeUpdate(sql)).isInstanceOfSatisfying(SQLException.class,
					failure -> assertThat(failure.getSQLState()).isEqualTo(sqlState));
		}
	}

	private Connection connection() throws SQLException {
		var connection = DriverManager.getConnection(url, username, password);
		if (schema != null) {
			connection.setSchema(schema);
		}
		return connection;
	}

	private static String requiredEnvironment(String name) {
		String value = System.getenv(name);
		if (value == null || value.isBlank()) {
			throw new IllegalStateException("Set " + name + " for postgres-integration");
		}
		return value;
	}
}
