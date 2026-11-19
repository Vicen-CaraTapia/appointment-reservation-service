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
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

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
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;

import ar.edu.um.prog2.appointmentreservation.AppointmentReservationApplication;
import ar.edu.um.prog2.appointmentreservation.reservation.application.ExternalPatientId;
import ar.edu.um.prog2.appointmentreservation.reservation.application.exception.ReservationPersistenceException;
import ar.edu.um.prog2.appointmentreservation.reservation.application.exception.ReservationProcessStateException;
import ar.edu.um.prog2.appointmentreservation.reservation.application.exception.ReservationProcessStateException.Reason;
import ar.edu.um.prog2.appointmentreservation.reservation.application.model.InitialReservationConfirmation;
import ar.edu.um.prog2.appointmentreservation.reservation.application.model.ConfirmationPatient;
import ar.edu.um.prog2.appointmentreservation.reservation.application.model.AppointmentHold;
import ar.edu.um.prog2.appointmentreservation.reservation.application.model.AuthenticatedReservationUser;
import ar.edu.um.prog2.appointmentreservation.reservation.application.model.StartReservationCommand;
import ar.edu.um.prog2.appointmentreservation.reservation.application.port.out.ReservationProcessStore;
import ar.edu.um.prog2.appointmentreservation.reservation.domain.model.ReservationProcessStatus;
import ar.edu.um.prog2.appointmentreservation.reservation.infrastructure.persistence.entity.ReservationProcessEntity;
import ar.edu.um.prog2.appointmentreservation.reservation.infrastructure.persistence.repository.ReservationProcessJpaRepository;
import ar.edu.um.prog2.appointmentreservation.support.CatedraIntegrationTestSupport;
import ar.edu.um.prog2.appointmentreservation.support.CatalogIntegrationTestSupport;
import ar.edu.um.prog2.appointmentreservation.support.FinalUserJwtTestSupport;
import ar.edu.um.prog2.appointmentreservation.user.infrastructure.persistence.entity.UserEntity;
import ar.edu.um.prog2.appointmentreservation.user.infrastructure.persistence.repository.UserJpaRepository;
import ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.rest.client.CatedraAppointmentHoldClient;
import ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.rest.dto.CatedraAppointmentHoldRequest;
import ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.rest.dto.CatedraAppointmentHoldResponse;
import ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.rest.exception.CatedraTimeoutException;
import ar.edu.um.prog2.appointmentreservation.professional.application.model.CurrentProfessional;
import ar.edu.um.prog2.appointmentreservation.professional.application.port.out.CurrentProfessionalSource;
import ar.edu.um.prog2.appointmentreservation.reservation.application.exception.HoldRequestException;
import ar.edu.um.prog2.appointmentreservation.reservation.application.exception.ReservationAuthenticationRequiredException;
import ar.edu.um.prog2.appointmentreservation.reservation.application.usecase.StartReservationProcessUseCase;
import ar.edu.um.prog2.appointmentreservation.user.application.model.RegisterUserCommand;
import ar.edu.um.prog2.appointmentreservation.user.application.port.out.FinalUserAuthenticator;
import ar.edu.um.prog2.appointmentreservation.user.application.usecase.RegisterUserUseCase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class ReservationProcessPersistenceIT {

	private static final LocalDate DATE = LocalDate.of(2026, 11, 17);
	private static final LocalTime TIME = LocalTime.of(11, 30, 0, 123456000);
	private static final Instant EXPIRY = Instant.parse("2026-11-17T14:40:00.123456Z");
	private static final String WORKFLOW_PASSWORD = "test-only-workflow-passphrase";
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
		startApplicationContext();
		String hash = context.getBean(PasswordEncoder.class).encode("test-only-process-persistence-passphrase");
		owner = users.saveAndFlush(new UserEntity("alice", hash, null, null, "alice@example.test", null, "es", "backend-test"));
	}

	private void startApplicationContext() {
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
				.sources(ReservationWorkflowDependencies.class)
				.initializers(CatedraIntegrationTestSupport::configure, CatalogIntegrationTestSupport::configure)
				.web(WebApplicationType.NONE)
				.initializers(application -> application.getEnvironment().getPropertySources().addFirst(
						new MapPropertySource("reservationProcessTest", properties)))
				.run();
		users = context.getBean(UserJpaRepository.class);
		processes = context.getBean(ReservationProcessJpaRepository.class);
		transactions = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
	}

	@AfterEach
	void closeAndRemoveOnlyThisTestSchema() throws SQLException {
		SecurityContextHolder.clearContext();
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
	void wiredWorkflowAuthenticatesRechecksCatalogAndCommitsTheOwnedHoldWithoutARemoteTransaction() {
		authenticateWorkflowUser();
		var catalog = context.getBean(CurrentProfessionalSource.class);
		var holdClient = context.getBean(CatedraAppointmentHoldClient.class);
		when(catalog.load(202)).thenAnswer(call -> {
			assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
			return workflowProfessional();
		});
		when(holdClient.createHold(any())).thenAnswer(call -> {
			assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
			return workflowHold();
		});
		var result = context.getBean(StartReservationProcessUseCase.class).start(workflowSelection());
		var stored = processes.findByIdAndOwner_Id(result.id(), owner.getId()).orElseThrow();
		assertThat(stored.getStatus()).isEqualTo(ReservationProcessStatus.HELD);
		assertThat(stored.getExternalPatientId()).isEqualTo("user-" + owner.getId());
		assertThat(stored.getProfessionalId()).isEqualTo(202);
		assertThat(stored.getAppointmentDate()).isEqualTo(DATE);
		assertThat(stored.getStartTime()).isEqualTo(LocalTime.of(11, 30));
		assertThat(stored.getHoldId()).isEqualTo("hold-workflow");
		assertThat(stored.getExternalReservationProcessId()).isEqualTo("process-workflow");
		assertThat(stored.getExpiresAt()).isEqualTo(EXPIRY);
		assertThat(stored.getCreatedBy()).isEqualTo("workflowuser");
		assertThat(stored.getCreatedDate()).isNotNull();
		assertThat(result.hold().expiresAt()).isEqualTo(EXPIRY);
		assertThat(processes.count()).isEqualTo(1);
		assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
		verify(catalog).load(202);
		verify(holdClient).createHold(new CatedraAppointmentHoldRequest(202, DATE, LocalTime.of(11, 30),
				"user-" + owner.getId()));
		verifyNoMoreInteractions(catalog, holdClient);
	}

	@Test
	void wiredWorkflowDoesNotCreateLocalStateOrRetryAfterAnAmbiguousRemoteTimeout() {
		authenticateWorkflowUser();
		var catalog = context.getBean(CurrentProfessionalSource.class);
		var holdClient = context.getBean(CatedraAppointmentHoldClient.class);
		when(catalog.load(202)).thenReturn(workflowProfessional());
		when(holdClient.createHold(any())).thenThrow(new CatedraTimeoutException());
		assertThatThrownBy(() -> context.getBean(StartReservationProcessUseCase.class).start(workflowSelection()))
				.isInstanceOfSatisfying(HoldRequestException.class, failure -> {
					assertThat(failure.reason()).isEqualTo(HoldRequestException.Reason.TIMEOUT);
					assertThat(failure).hasNoCause();
				});
		assertThat(processes.count()).isZero();
		verify(holdClient).createHold(any());
		verifyNoMoreInteractions(holdClient);
	}

	@Test
	void wiredWorkflowReportsLocalFailureAfterRemoteSuccessWithoutRepeatingTheHold() {
		authenticateWorkflowUser();
		var catalog = context.getBean(CurrentProfessionalSource.class);
		var holdClient = context.getBean(CatedraAppointmentHoldClient.class);
		when(catalog.load(202)).thenReturn(workflowProfessional());
		when(holdClient.createHold(any())).thenAnswer(call -> {
			assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
			// Simulate owner deletion between authentication and local persistence, after remote acceptance.
			users.deleteById(owner.getId());
			return workflowHold();
		});
		assertThatThrownBy(() -> context.getBean(StartReservationProcessUseCase.class).start(workflowSelection()))
				.isInstanceOf(ReservationPersistenceException.class).hasNoCause();
		assertThat(processes.count()).isZero();
		verify(holdClient).createHold(any());
		verifyNoMoreInteractions(holdClient);
	}

	@Test
	void wiredWorkflowRejectsAnAbsentSecurityIdentityBeforeExternalWorkOrLocalWrites() {
		SecurityContextHolder.clearContext();
		assertThatThrownBy(() -> context.getBean(StartReservationProcessUseCase.class).start(workflowSelection()))
				.isInstanceOf(ReservationAuthenticationRequiredException.class);
		verifyNoInteractions(context.getBean(CurrentProfessionalSource.class), context.getBean(CatedraAppointmentHoldClient.class));
		assertThat(processes.count()).isZero();
	}

	private void authenticateWorkflowUser() {
		context.getBean(RegisterUserUseCase.class).register(new RegisterUserCommand("workflowuser", WORKFLOW_PASSWORD,
				null, null, "workflow@example.test", null, "es"));
		owner = users.findByLoginIgnoreCase("workflowuser").orElseThrow();
		var token = context.getBean(FinalUserAuthenticator.class).authenticate("WORKFLOWUSER", WORKFLOW_PASSWORD, false);
		var jwt = context.getBean(JwtDecoder.class).decode(token.value());
		var authentication = new JwtAuthenticationConverter().convert(jwt);
		var securityContext = SecurityContextHolder.createEmptyContext();
		securityContext.setAuthentication(authentication);
		SecurityContextHolder.setContext(securityContext);
	}

	private static StartReservationCommand workflowSelection() {
		return new StartReservationCommand(202, DATE, LocalTime.of(11, 30));
	}

	private static CurrentProfessional workflowProfessional() {
		return new CurrentProfessional(202, "Martin", "Rivas", true,
				new CurrentProfessional.Category(101, "Cardiologia", true),
				List.of(new CurrentProfessional.WeeklySchedule(303, DATE.getDayOfWeek(),
						LocalTime.of(11, 30), LocalTime.of(14, 30), 30, true)));
	}

	private static CatedraAppointmentHoldResponse workflowHold() {
		return new CatedraAppointmentHoldResponse("hold-workflow", "process-workflow", "HELD", EXPIRY);
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class ReservationWorkflowDependencies {
		@Bean
		@Primary
		CurrentProfessionalSource controlledWorkflowCatalog() {
			return mock(CurrentProfessionalSource.class);
		}

		@Bean
		@Primary
		CatedraAppointmentHoldClient controlledWorkflowHoldClient() {
			return mock(CatedraAppointmentHoldClient.class);
		}
	}

	@Test
	void applicationStoreCommitsACompleteHeldProcessOwnedAndAuditedByTheAuthenticatedUser() {
		var input = new AppointmentHold("hold-store-" + "x".repeat(300), "process-store-" + "y".repeat(300), EXPIRY);
		var result = store().createHeld(identity(owner), selection(), input);
		var saved = processes.findByIdAndOwner_Id(result.id(), owner.getId()).orElseThrow();
		assertThat(result.id()).isPositive();
		assertThat(result.hold()).isEqualTo(input);
		assertThat(saved.getOwner().getId()).isEqualTo(owner.getId());
		assertThat(saved.getExternalPatientId()).isEqualTo("user-" + owner.getId());
		assertThat(saved.getProfessionalId()).isEqualTo(Long.MAX_VALUE);
		assertThat(saved.getAppointmentDate()).isEqualTo(DATE);
		assertThat(saved.getStartTime()).isEqualTo(TIME);
		assertThat(saved.getStatus()).isEqualTo(ReservationProcessStatus.HELD);
		assertThat(saved.getHoldId()).isEqualTo(input.holdId());
		assertThat(saved.getExternalReservationProcessId()).isEqualTo(input.reservationProcessId());
		assertThat(saved.getExpiresAt()).isEqualTo(EXPIRY);
		assertThat(saved.getCreatedBy()).isEqualTo(owner.getLogin());
		assertThat(saved.getCreatedDate()).isNotNull();
		assertThat(saved.getLastModifiedBy()).isNull();
		assertThat(saved.getLastModifiedDate()).isNull();
		assertThat(processes.count()).isEqualTo(1);
	}

	@Test
	void applicationStoreNeverRoundsNanosecondExpiryUpWhenPostgresStoresMicroseconds() {
		var remote = new AppointmentHold("hold-nanos", "process-nanos", Instant.parse("2026-11-17T22:28:18.514830573Z"));
		var result = store().createHeld(identity(owner), selection(), remote);
		var saved = processes.findById(result.id()).orElseThrow();
		assertThat(saved.getExpiresAt()).isEqualTo(Instant.parse("2026-11-17T22:28:18.514830Z"))
				.isBeforeOrEqualTo(remote.expiresAt());
		assertThat(result.hold().expiresAt()).isEqualTo(saved.getExpiresAt());
	}

	@Test
	void applicationStoreRollsBackAnAbsentOwnerWithoutExposingSqlDiagnostics() {
		var absent = new AuthenticatedReservationUser(Long.MAX_VALUE, "absent-user");
		assertThatThrownBy(() -> store().createHeld(absent, selection(), new AppointmentHold("hold-absent", "process-absent", EXPIRY)))
				.isInstanceOf(ReservationPersistenceException.class)
				.hasMessage("Reservation process could not be persisted; the remote hold may still exist").hasNoCause();
		assertThat(processes.count()).isZero();
	}

	@ParameterizedTest
	@ValueSource(strings = {"hold", "external-process"})
	void applicationStoreRollsBackDuplicateExternalIdsWithoutLeavingAPendingProcess(String duplicate) {
		var first = store().createHeld(identity(owner), selection(), new AppointmentHold("hold-first", "process-first", EXPIRY));
		var repeated = new AppointmentHold(duplicate.equals("hold") ? "hold-first" : "hold-second",
				duplicate.equals("external-process") ? "process-first" : "process-second", EXPIRY);
		assertThatThrownBy(() -> store().createHeld(identity(owner), selection(), repeated))
				.isInstanceOf(ReservationPersistenceException.class).hasNoCause();
		assertThat(processes.count()).isEqualTo(1);
		assertThat(processes.findById(first.id()).orElseThrow().getStatus()).isEqualTo(ReservationProcessStatus.HELD);
	}

	@Test
	void applicationStoreDerivesPatientIdentityFromEachOwnerAndPreservesOwnershipScopedLookup() {
		var other = users.saveAndFlush(new UserEntity("bob", owner.getPasswordHash(), null, null,
				"bob@example.test", null, "es", "backend-test"));
		var first = store().createHeld(identity(owner), selection(), new AppointmentHold("hold-alice", "process-alice", EXPIRY));
		var second = store().createHeld(identity(other), selection(), new AppointmentHold("hold-bob", "process-bob", EXPIRY));
		assertThat(processes.findByIdAndOwner_Id(first.id(), other.getId())).isEmpty();
		var saved = processes.findByIdAndOwner_Id(second.id(), other.getId()).orElseThrow();
		assertThat(saved.getExternalPatientId()).isEqualTo("user-" + other.getId());
		assertThat(saved.getCreatedBy()).isEqualTo("bob");
		assertThat(processes.count()).isEqualTo(2);
	}

	private ReservationProcessStore store() {
		return context.getBean(ReservationProcessStore.class);
	}

	private static AuthenticatedReservationUser identity(UserEntity user) {
		return new AuthenticatedReservationUser(user.getId(), user.getLogin());
	}

	private static StartReservationCommand selection() {
		return new StartReservationCommand(Long.MAX_VALUE, DATE, TIME);
	}

	@Test
	void appliesLaterMigrationsAfterAnExistingV1WithoutReapplyingThem() {
		var flyway = context.getBean(Flyway.class);
		assertThat(flyway.info().applied()).extracting(info -> info.getVersion().toString()).containsExactly("1", "2", "3", "4");
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

	@Test
	void persistsInitialConfirmationAndAuditWithoutChangingOwnershipOrExternalIdentity() {
		var held = pending(owner);
		held.markHeld("hold-confirm", "process-confirm", EXPIRY, "actor");
		var saved = processes.saveAndFlush(held);
		var initialVersion = saved.getVersion();
		var created = processes.findById(saved.getId()).orElseThrow().getCreatedDate();
		transactions.executeWithoutResult(status -> {
			var process = processes.findByIdAndOwner_Id(saved.getId(), owner.getId()).orElseThrow();
			process.claimConfirmation("Ana", "Perez", "confirmation-adapter");
			process.markWaitingForPhone("Ana", "Perez", EXPIRY.plusNanos(789), "confirmation-adapter");
			processes.flush();
		});
		var actual = processes.findById(saved.getId()).orElseThrow();
		assertThat(actual.getStatus()).isEqualTo(ReservationProcessStatus.WAITING_FOR_PHONE);
		assertThat(actual.getPatientFirstName()).isEqualTo("Ana");
		assertThat(actual.getPatientLastName()).isEqualTo("Perez");
		assertThat(actual.getExpiresAt()).isEqualTo(EXPIRY);
		assertThat(actual.getHoldId()).isEqualTo("hold-confirm");
		assertThat(actual.getExternalReservationProcessId()).isEqualTo("process-confirm");
		assertThat(actual.getOwner().getId()).isEqualTo(owner.getId());
		assertThat(actual.getExternalPatientId()).isEqualTo(ExternalPatientId.fromUserId(owner.getId()));
		assertThat(actual.getCreatedDate()).isEqualTo(created);
		assertThat(actual.getCreatedBy()).isEqualTo("backend-test");
		assertThat(actual.getLastModifiedBy()).isEqualTo("confirmation-adapter");
		assertThat(actual.getLastModifiedDate()).isAfterOrEqualTo(created);
		assertThat(actual.getVersion()).isEqualTo(initialVersion + 1);
	}

	@Test
	void rejectsAStaleConcurrentConfirmationWithoutOverwritingTheWinner() {
		var held = pending(owner);
		held.markHeld("hold-concurrent", "process-concurrent", EXPIRY, "actor");
		long id = processes.saveAndFlush(held).getId();
		// Two independent persistence contexts observed the same committed version.
		var first = processes.findById(id).orElseThrow();
		var stale = processes.findById(id).orElseThrow();
		assertThat(stale.getVersion()).isEqualTo(first.getVersion());
		first.claimConfirmation("Ana", "Perez", "winner");
		first.markWaitingForPhone("Ana", "Perez", EXPIRY, "winner");
		processes.saveAndFlush(first);
		stale.claimConfirmation("Other", "Patient", "stale-writer");
		stale.markWaitingForPhone("Other", "Patient", EXPIRY.plusSeconds(60), "stale-writer");
		assertThatThrownBy(() -> processes.saveAndFlush(stale))
				.isInstanceOf(OptimisticLockingFailureException.class);
		var actual = processes.findById(id).orElseThrow();
		assertThat(actual.getPatientFirstName()).isEqualTo("Ana");
		assertThat(actual.getExpiresAt()).isEqualTo(EXPIRY);
		assertThat(actual.getLastModifiedBy()).isEqualTo("winner");
		assertThat(actual.getVersion()).isEqualTo(1L);
	}

	@Test
	void rollsBackConfirmationNamesStateExpiryAuditAndVersionTogether() {
		var held = pending(owner);
		held.markHeld("hold-rollback", "process-rollback", EXPIRY, "actor");
		long id = processes.saveAndFlush(held).getId();
		assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
			var process = processes.findById(id).orElseThrow();
			process.claimConfirmation("Ana", "Perez", "actor");
			process.markWaitingForPhone("Ana", "Perez", EXPIRY.plusSeconds(1), "actor");
			processes.flush();
			throw new IllegalStateException("Controlled failure after flush");
		})).isInstanceOf(IllegalStateException.class);
		var actual = processes.findById(id).orElseThrow();
		assertThat(actual.getStatus()).isEqualTo(ReservationProcessStatus.HELD);
		assertThat(actual.getPatientFirstName()).isNull();
		assertThat(actual.getPatientLastName()).isNull();
		assertThat(actual.getExpiresAt()).isEqualTo(EXPIRY);
		assertThat(actual.getLastModifiedBy()).isNull();
		assertThat(actual.getLastModifiedDate()).isNull();
		assertThat(actual.getVersion()).isZero();
	}

	@Test
	void databaseRequiresPatientAndHoldDetailsForWaitingEvenWhenJpaIsBypassed() throws SQLException {
		var held = pending(owner);
		held.markHeld("hold-sql", "process-sql", EXPIRY, "actor");
		long id = processes.saveAndFlush(held).getId();
		String transition = "UPDATE reservation_process SET process_status='WAITING_FOR_PHONE', ";
		for (String fields : List.of(
				"patient_first_name=NULL, patient_last_name='Perez'",
				"patient_first_name='Ana', patient_last_name=NULL",
				"patient_first_name='A', patient_last_name='Perez'",
				"patient_first_name='Ana', patient_last_name='P'",
				"patient_first_name=E'\\t\\n', patient_last_name='Perez'",
				"patient_first_name='Ana', patient_last_name='  '",
				"patient_first_name='Ana', patient_last_name='Perez', hold_id=NULL",
				"patient_first_name='Ana', patient_last_name='Perez', external_reservation_process_id=NULL",
				"patient_first_name='Ana', patient_last_name='Perez', expires_at=NULL")) {
			assertSqlRejected(transition + fields + " WHERE id=" + id, "23514");
		}
		for (String name : List.of("patient_first_name", "patient_last_name")) {
			assertSqlRejected("UPDATE reservation_process SET " + name + "=repeat('a',101) WHERE id=" + id, "22001");
		}
		assertSqlRejected("UPDATE reservation_process SET version=NULL WHERE id=" + id, "23502");
		var unchanged = processes.findById(id).orElseThrow();
		assertThat(unchanged.getStatus()).isEqualTo(ReservationProcessStatus.HELD);
		assertThat(unchanged.getPatientFirstName()).isNull();
	}

	@Test
	void applicationPortReadsPendingAndHeldObservationsAndCommitsTheInitialAcceptance() {
		long pendingId = processes.saveAndFlush(pending(owner)).getId();
		var pending = store().findOwned(pendingId, identity(owner)).orElseThrow();
		assertThat(pending.status()).isEqualTo(ReservationProcessStatus.PENDING_HOLD);
		assertThat(pending.hold()).isNull();
		var held = store().createHeld(identity(owner), selection(),
				new AppointmentHold("hold-port", "process-port", EXPIRY));
		var observed = store().findOwned(held.id(), identity(owner)).orElseThrow();
		assertThat(observed.version()).isZero();
		assertThat(observed.selection()).isEqualTo(selection());
		var confirmation = new InitialReservationConfirmation("process-port", " Ana ", "Perez", EXPIRY.plusNanos(789));
		var claim = store().claimInitialConfirmation(held.id(), observed.version(), identity(owner),
				new ConfirmationPatient(" Ana ", "Perez"), EXPIRY.minusSeconds(1));
		var result = store().recordInitialConfirmation(held.id(), claim.version(), identity(owner), confirmation);
		assertThat(result.status()).isEqualTo(ReservationProcessStatus.WAITING_FOR_PHONE);
		assertThat(result.version()).isEqualTo(2);
		assertThat(result.patientFirstName()).isEqualTo(" Ana ");
		assertThat(result.patientLastName()).isEqualTo("Perez");
		assertThat(result.externalPatientId()).isEqualTo(ExternalPatientId.fromUserId(owner.getId()));
		assertThat(result.hold().expiresAt()).isEqualTo(EXPIRY);
		assertThat(store().findOwned(held.id(), identity(owner))).contains(result);
		assertThat(processes.findById(held.id()).orElseThrow().getLastModifiedBy()).isEqualTo(owner.getLogin());
		assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
	}

	@Test
	void applicationPortDoesNotRevealOrUpdateAnotherUsersProcess() {
		var other = users.saveAndFlush(new UserEntity("bob", owner.getPasswordHash(), null, null,
				"bob@example.test", null, "es", "actor"));
		var held = store().createHeld(identity(owner), selection(), new AppointmentHold("hold-owned-port", "process-owned-port", EXPIRY));
		assertThat(store().findOwned(held.id(), identity(other))).isEmpty();
		assertThat(store().findOwned(Long.MAX_VALUE, identity(other))).isEmpty();
		var accepted = new InitialReservationConfirmation("process-owned-port", "Ana", "Perez", EXPIRY);
		for (long id : List.of(held.id(), Long.MAX_VALUE)) {
			assertThatThrownBy(() -> store().recordInitialConfirmation(id, 0, identity(other), accepted))
					.isInstanceOfSatisfying(ReservationProcessStateException.class,
							failure -> assertThat(failure.reason()).isEqualTo(Reason.NOT_FOUND)).hasNoCause();
		}
		var actual = processes.findById(held.id()).orElseThrow();
		assertThat(actual.getStatus()).isEqualTo(ReservationProcessStatus.HELD);
		assertThat(actual.getVersion()).isZero();
		assertThat(actual.getPatientFirstName()).isNull();
	}

	@Test
	void applicationPortRejectsRepeatedOrStaleAcceptanceWithoutReplacingTheFirstPatient() {
		var held = store().createHeld(identity(owner), selection(), new AppointmentHold("hold-repeated-port", "process-repeated-port", EXPIRY));
		var accepted = new InitialReservationConfirmation("process-repeated-port", "Ana", "Perez", EXPIRY);
		var claim = store().claimInitialConfirmation(held.id(), 0, identity(owner),
				new ConfirmationPatient("Ana", "Perez"), EXPIRY.minusSeconds(1));
		store().recordInitialConfirmation(held.id(), claim.version(), identity(owner), accepted);
		for (long version : List.of(0L, 1L, 2L)) {
			assertThatThrownBy(() -> store().recordInitialConfirmation(held.id(), version, identity(owner),
					new InitialReservationConfirmation("process-repeated-port", "Other", "Patient", EXPIRY.plusSeconds(60))))
					.isInstanceOfSatisfying(ReservationProcessStateException.class,
							failure -> assertThat(failure.reason()).isEqualTo(Reason.CONFLICT));
		}
		var actual = processes.findById(held.id()).orElseThrow();
		assertThat(actual.getPatientFirstName()).isEqualTo("Ana");
		assertThat(actual.getExpiresAt()).isEqualTo(EXPIRY);
		assertThat(actual.getVersion()).isEqualTo(2L);
	}

	@Test
	void applicationPortRejectsAnUnrelatedRemoteProcessWithoutChangingLocalState() {
		var held = store().createHeld(identity(owner), selection(), new AppointmentHold("hold-correlation-port", "process-correlation-port", EXPIRY));
		var claim = store().claimInitialConfirmation(held.id(), 0, identity(owner), new ConfirmationPatient("Ana", "Perez"), EXPIRY.minusSeconds(1));
		assertThatThrownBy(() -> store().recordInitialConfirmation(held.id(), claim.version(), identity(owner),
				new InitialReservationConfirmation("process-other", "Ana", "Perez", EXPIRY)))
				.isInstanceOfSatisfying(ReservationProcessStateException.class,
						failure -> assertThat(failure.reason()).isEqualTo(Reason.CONFLICT));
		var actual = processes.findById(held.id()).orElseThrow();
		assertThat(actual.getStatus()).isEqualTo(ReservationProcessStatus.CONFIRMATION_PENDING);
		assertThat(actual.getExternalReservationProcessId()).isEqualTo("process-correlation-port");
		assertThat(actual.getVersion()).isEqualTo(claim.version());
	}

	@Test
	void applicationPortRollsBackDatabaseFailureAndDoesNotLeakPatientOrSqlDiagnostics() throws SQLException {
		var held = store().createHeld(identity(owner), selection(), new AppointmentHold("hold-failure-port", "process-failure-port", EXPIRY));
		var claim = store().claimInitialConfirmation(held.id(), 0, identity(owner), new ConfirmationPatient("PrivatePatient", "PrivateSurname"), EXPIRY.minusSeconds(1));
		// Isolated schema only: force a real database rejection after entity mutation.
		try (var connection = connection(); var statement = connection.createStatement()) {
			statement.execute("ALTER TABLE reservation_process ADD CONSTRAINT test_reject_confirmation"
					+ " CHECK (process_status <> 'WAITING_FOR_PHONE')");
		}
		assertThatThrownBy(() -> store().recordInitialConfirmation(held.id(), claim.version(), identity(owner),
				new InitialReservationConfirmation("process-failure-port", "PrivatePatient", "PrivateSurname", EXPIRY.plusSeconds(1))))
				.isInstanceOfSatisfying(ReservationProcessStateException.class,
						failure -> assertThat(failure.reason()).isEqualTo(Reason.STORAGE_FAILURE))
				.hasNoCause().hasMessageNotContaining("PrivatePatient").hasMessageNotContaining("test_reject_confirmation");
		var actual = processes.findById(held.id()).orElseThrow();
		assertThat(actual.getStatus()).isEqualTo(ReservationProcessStatus.CONFIRMATION_PENDING);
		assertThat(actual.getPatientFirstName()).isEqualTo("PrivatePatient");
		assertThat(actual.getPatientLastName()).isEqualTo("PrivateSurname");
		assertThat(actual.getExpiresAt()).isEqualTo(EXPIRY);
		assertThat(actual.getLastModifiedBy()).isEqualTo(owner.getLogin());
		assertThat(actual.getLastModifiedDate()).isNotNull();
		assertThat(actual.getVersion()).isEqualTo(claim.version());
	}

	@Test
	void onlyOneConcurrentRequestCanCommitAConfirmationClaim() throws Exception {
		var held = store().createHeld(identity(owner), selection(), new AppointmentHold("hold-race", "process-race", EXPIRY));
		var authenticated = identity(owner);
		var ready = new CountDownLatch(2);
		var start = new CountDownLatch(1);
		Callable<String> task = () -> {
			ready.countDown();
			assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
			try {
				var claim = store().claimInitialConfirmation(held.id(), 0, authenticated,
						new ConfirmationPatient("Ana", "Perez"), EXPIRY.minusSeconds(1));
				assertThat(claim.version()).isEqualTo(1);
				return "CLAIMED";
			} catch (ReservationProcessStateException failure) {
				assertThat(failure.reason()).isEqualTo(Reason.CONFLICT);
				return "CONFLICT";
			}
		};
		try (var executor = Executors.newFixedThreadPool(2)) {
			var first = executor.submit(task);
			var second = executor.submit(task);
			try {
				assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
			} finally {
				start.countDown();
			}
			assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
					.containsExactlyInAnyOrder("CLAIMED", "CONFLICT");
		}
		var actual = store().findOwned(held.id(), authenticated).orElseThrow();
		assertThat(actual.status()).isEqualTo(ReservationProcessStatus.CONFIRMATION_PENDING);
		assertThat(actual.patientFirstName()).isEqualTo("Ana");
		assertThat(actual.hold().expiresAt()).isEqualTo(EXPIRY);
		assertThat(processes.count()).isEqualTo(1);
	}

	@Test
	void pendingClaimSurvivesApplicationRestartAndExpiryDoesNotAuthorizeAnotherPost() {
		var authenticated = identity(owner);
		var held = store().createHeld(authenticated, selection(), new AppointmentHold("hold-restart", "process-restart", EXPIRY));
		var claim = store().claimInitialConfirmation(held.id(), 0, authenticated,
				new ConfirmationPatient("Ana", "Perez"), EXPIRY.minusSeconds(1));
		context.close();
		startApplicationContext();
		assertThat(store().findOwned(held.id(), authenticated)).contains(claim);
		assertThatThrownBy(() -> store().claimInitialConfirmation(held.id(), claim.version(), authenticated,
				new ConfirmationPatient("Other", "Patient"), EXPIRY.plusSeconds(60)))
				.isInstanceOfSatisfying(ReservationProcessStateException.class,
						failure -> assertThat(failure.reason()).isEqualTo(Reason.CONFLICT));
		assertThat(store().findOwned(held.id(), authenticated)).contains(claim);
	}

	@Test
	void aReleasedAttemptCannotCompleteOrReleaseASubsequentAttemptWithItsOldVersion() {
		var authenticated = identity(owner);
		var held = store().createHeld(authenticated, selection(), new AppointmentHold("hold-attempts", "process-attempts", EXPIRY));
		var first = store().claimInitialConfirmation(held.id(), 0, authenticated,
				new ConfirmationPatient("Ana", "Perez"), EXPIRY.minusSeconds(1));
		var released = store().releaseInitialConfirmation(held.id(), first.version(), authenticated);
		assertThat(released.status()).isEqualTo(ReservationProcessStatus.HELD);
		assertThat(released.patientFirstName()).isNull();
		assertThat(released.patientLastName()).isNull();
		assertThat(released.hold()).isEqualTo(first.hold());
		assertThat(released.version()).isEqualTo(2);
		var second = store().claimInitialConfirmation(held.id(), released.version(), authenticated,
				new ConfirmationPatient("Other", "Patient"), EXPIRY.minusSeconds(1));
		assertThat(second.version()).isEqualTo(3);
		assertThatThrownBy(() -> store().releaseInitialConfirmation(held.id(), first.version(), authenticated))
				.isInstanceOfSatisfying(ReservationProcessStateException.class,
						failure -> assertThat(failure.reason()).isEqualTo(Reason.CONFLICT));
		assertThatThrownBy(() -> store().recordInitialConfirmation(held.id(), first.version(), authenticated,
				new InitialReservationConfirmation("process-attempts", "Ana", "Perez", EXPIRY)))
				.isInstanceOfSatisfying(ReservationProcessStateException.class,
						failure -> assertThat(failure.reason()).isEqualTo(Reason.CONFLICT));
		assertThat(store().findOwned(held.id(), authenticated)).contains(second);
		var accepted = store().recordInitialConfirmation(held.id(), second.version(), authenticated,
				new InitialReservationConfirmation("process-attempts", "Other", "Patient", EXPIRY));
		assertThat(accepted.status()).isEqualTo(ReservationProcessStatus.WAITING_FOR_PHONE);
		assertThat(accepted.version()).isEqualTo(4);
		assertThatThrownBy(() -> store().releaseInitialConfirmation(held.id(), accepted.version(), authenticated))
				.isInstanceOfSatisfying(ReservationProcessStateException.class,
						failure -> assertThat(failure.reason()).isEqualTo(Reason.CONFLICT));
	}

	@Test
	void neitherClaimNorReleaseCanAccessAnotherUsersProcess() {
		var held = store().createHeld(identity(owner), selection(), new AppointmentHold("hold-ownership", "process-ownership", EXPIRY));
		var other = users.saveAndFlush(new UserEntity("bob", owner.getPasswordHash(), null, null,
				"bob@example.test", null, "es", "actor"));
		assertThatThrownBy(() -> store().claimInitialConfirmation(held.id(), 0, identity(other),
				new ConfirmationPatient("Ana", "Perez"), EXPIRY.minusSeconds(1)))
				.isInstanceOfSatisfying(ReservationProcessStateException.class,
						failure -> assertThat(failure.reason()).isEqualTo(Reason.NOT_FOUND));
		var claim = store().claimInitialConfirmation(held.id(), 0, identity(owner),
				new ConfirmationPatient("Ana", "Perez"), EXPIRY.minusSeconds(1));
		assertThatThrownBy(() -> store().releaseInitialConfirmation(held.id(), claim.version(), identity(other)))
				.isInstanceOfSatisfying(ReservationProcessStateException.class,
						failure -> assertThat(failure.reason()).isEqualTo(Reason.NOT_FOUND));
		assertThat(store().findOwned(held.id(), identity(owner))).contains(claim);
	}

	@Test
	void claimAtItsExpirationIsRejectedWithoutInventingARemoteOutcome() {
		var held = store().createHeld(identity(owner), selection(), new AppointmentHold("hold-expired-claim", "process-expired-claim", EXPIRY));
		assertThatThrownBy(() -> store().claimInitialConfirmation(held.id(), 0, identity(owner),
				new ConfirmationPatient("Ana", "Perez"), EXPIRY))
				.isInstanceOfSatisfying(ReservationProcessStateException.class,
						failure -> assertThat(failure.reason()).isEqualTo(Reason.EXPIRED));
		var actual = processes.findById(held.id()).orElseThrow();
		assertThat(actual.getStatus()).isEqualTo(ReservationProcessStatus.HELD);
		assertThat(actual.getVersion()).isZero();
		assertThat(actual.getPatientFirstName()).isNull();
		assertThat(actual.getLastModifiedDate()).isNull();
	}

	@Test
	void failedClaimAndFailedReleaseRollBackTheirWholeLocalTransition() throws SQLException {
		var held = store().createHeld(identity(owner), selection(), new AppointmentHold("hold-claim-rollback", "process-claim-rollback", EXPIRY));
		try (var connection = connection(); var statement = connection.createStatement()) {
			statement.execute("ALTER TABLE reservation_process ADD CONSTRAINT test_reject_claim CHECK (process_status <> 'CONFIRMATION_PENDING')");
		}
		assertThatThrownBy(() -> store().claimInitialConfirmation(held.id(), 0, identity(owner),
				new ConfirmationPatient("Ana", "Perez"), EXPIRY.minusSeconds(1)))
				.isInstanceOfSatisfying(ReservationProcessStateException.class,
						failure -> assertThat(failure.reason()).isEqualTo(Reason.STORAGE_FAILURE)).hasNoCause();
		var actual = processes.findById(held.id()).orElseThrow();
		assertThat(actual.getStatus()).isEqualTo(ReservationProcessStatus.HELD);
		assertThat(actual.getPatientFirstName()).isNull();
		assertThat(actual.getLastModifiedBy()).isNull();
		assertThat(actual.getVersion()).isZero();
		try (var connection = connection(); var statement = connection.createStatement()) {
			statement.execute("ALTER TABLE reservation_process DROP CONSTRAINT test_reject_claim");
		}
		var claim = store().claimInitialConfirmation(held.id(), 0, identity(owner),
				new ConfirmationPatient("Ana", "Perez"), EXPIRY.minusSeconds(1));
		var audit = processes.findById(held.id()).orElseThrow().getLastModifiedDate();
		try (var connection = connection(); var statement = connection.createStatement()) {
			statement.execute("ALTER TABLE reservation_process ADD CONSTRAINT test_reject_release CHECK (process_status <> 'HELD')");
		}
		assertThatThrownBy(() -> store().releaseInitialConfirmation(held.id(), claim.version(), identity(owner)))
				.isInstanceOfSatisfying(ReservationProcessStateException.class,
						failure -> assertThat(failure.reason()).isEqualTo(Reason.STORAGE_FAILURE)).hasNoCause();
		assertThat(store().findOwned(held.id(), identity(owner))).contains(claim);
		assertThat(processes.findById(held.id()).orElseThrow().getLastModifiedDate()).isEqualTo(audit);
	}

	@Test
	void pendingClaimRequiresBothPatientNamesExternalIdsAndExpirationInSql() throws SQLException {
		var held = store().createHeld(identity(owner), selection(), new AppointmentHold("hold-claim-sql", "process-claim-sql", EXPIRY));
		String update = "UPDATE reservation_process SET process_status='CONFIRMATION_PENDING', ";
		for (String fields : List.of(
				"patient_first_name=NULL, patient_last_name='Perez'",
				"patient_first_name='Ana', patient_last_name=NULL",
				"patient_first_name='Ana', patient_last_name=' '",
				"patient_first_name='Ana', patient_last_name='Perez', hold_id=NULL",
				"patient_first_name='Ana', patient_last_name='Perez', external_reservation_process_id=NULL",
				"patient_first_name='Ana', patient_last_name='Perez', expires_at=NULL")) {
			assertSqlRejected(update + fields + " WHERE id=" + held.id(), "23514");
		}
		assertThat(processes.findById(held.id()).orElseThrow().getStatus()).isEqualTo(ReservationProcessStatus.HELD);
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
