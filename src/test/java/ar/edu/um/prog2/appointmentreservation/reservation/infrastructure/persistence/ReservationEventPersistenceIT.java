package ar.edu.um.prog2.appointmentreservation.reservation.infrastructure.persistence;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
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
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import ar.edu.um.prog2.appointmentreservation.AppointmentReservationApplication;
import ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.kafka.event.CatedraAppointmentActionDecoder;
import ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.kafka.exception.CatedraActionStorageException;
import ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.kafka.exception.InvalidCatedraActionException;
import ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.kafka.exception.InvalidCatedraActionException.Reason;
import ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.kafka.persistence.RejectedCatedraActionStore;
import ar.edu.um.prog2.appointmentreservation.reservation.application.exception.ReservationEventStorageException;
import ar.edu.um.prog2.appointmentreservation.reservation.application.exception.ReservationProcessStateException;
import ar.edu.um.prog2.appointmentreservation.reservation.application.model.AuthenticatedReservationUser;
import ar.edu.um.prog2.appointmentreservation.reservation.application.model.ConfirmationPatient;
import ar.edu.um.prog2.appointmentreservation.reservation.application.model.InitialReservationConfirmation;
import ar.edu.um.prog2.appointmentreservation.reservation.application.model.ReservationActionEvent;
import ar.edu.um.prog2.appointmentreservation.reservation.application.model.ReservationEventReceipt;
import ar.edu.um.prog2.appointmentreservation.reservation.application.model.ReservationEventReceipt.State;
import ar.edu.um.prog2.appointmentreservation.reservation.application.model.StartReservationCommand;
import ar.edu.um.prog2.appointmentreservation.reservation.application.port.out.ReservationEventStore;
import ar.edu.um.prog2.appointmentreservation.reservation.application.port.out.PhoneSubmissionStore;
import ar.edu.um.prog2.appointmentreservation.reservation.application.model.NewPhoneSubmission;
import ar.edu.um.prog2.appointmentreservation.reservation.application.model.PhoneSubmissionIntent;
import ar.edu.um.prog2.appointmentreservation.reservation.application.exception.PhoneSubmissionStateException;
import ar.edu.um.prog2.appointmentreservation.reservation.application.port.out.ReservationProcessStore;
import ar.edu.um.prog2.appointmentreservation.reservation.application.usecase.ProcessReservationActionUseCase;
import ar.edu.um.prog2.appointmentreservation.reservation.domain.model.AsynchronousReservationTransitions.Signal;
import ar.edu.um.prog2.appointmentreservation.reservation.domain.model.ReservationProcessStatus;
import ar.edu.um.prog2.appointmentreservation.reservation.infrastructure.persistence.entity.ReservationProcessEntity;
import ar.edu.um.prog2.appointmentreservation.reservation.infrastructure.persistence.repository.ReservationEventInboxJpaRepository;
import ar.edu.um.prog2.appointmentreservation.reservation.infrastructure.persistence.repository.ReservationProcessJpaRepository;
import ar.edu.um.prog2.appointmentreservation.support.CatedraIntegrationTestSupport;
import ar.edu.um.prog2.appointmentreservation.support.CatalogIntegrationTestSupport;
import ar.edu.um.prog2.appointmentreservation.support.FinalUserJwtTestSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

@ExtendWith(OutputCaptureExtension.class)
class ReservationEventPersistenceIT {
	private static final Instant TIME = Instant.parse("2026-11-20T12:00:00.123456789Z");
	private static final Instant EXPIRY = TIME.plusSeconds(300);
	private static final StartReservationCommand SELECTION = new StartReservationCommand(202, LocalDate.of(2030, 1, 3), LocalTime.of(18, 0));
	private static final String ACTIONS_TOPIC = "test.appointment.actions";
	private static final String REJECTED_PAYLOAD = "{\"technicalJwt\":\"not-a-real-catedra-token\",\"phone\":\"00000001234\"";
	private String url;
	private String username;
	private String password;
	private String schema;
	private long id;
	private long ownerId;
	private ConfigurableApplicationContext context;
	private ReservationEventStore events;
	private ReservationProcessStore store;
	private ReservationProcessJpaRepository processes;
	private PhoneSubmissionStore phones;
	private ProcessReservationActionUseCase actions;
	private RejectedCatedraActionStore rejectedActions;
	private boolean prettyHttpJson;

	@BeforeEach
	void upgradeAnIsolatedV4SchemaWithAnExistingOwnedHold() throws Exception {
		url = required("APPOINTMENT_TEST_DB_URL");
		username = required("APPOINTMENT_TEST_DB_USERNAME");
		password = required("APPOINTMENT_TEST_DB_PASSWORD");
		try (var connection = connection(); var statement = connection.createStatement()) {
			try (var result = statement.executeQuery("SELECT current_database()")) {
				result.next();
				assertThat(result.getString(1)).startsWith("appointment_");
			}
			String candidate = "appointment_event_it_" + UUID.randomUUID().toString().replace("-", "");
			statement.execute("CREATE SCHEMA " + candidate);
			schema = candidate;
		}
		Flyway.configure().dataSource(url, username, password).schemas(schema).defaultSchema(schema)
				.createSchemas(false).locations("classpath:db/migration").target("4").load().migrate();
		try (var connection = connection(); var statement = connection.createStatement()) {
			try (var result = statement.executeQuery("INSERT INTO " + schema + ".app_user"
					+ " (login,email,password_hash,lang_key,created_by) VALUES ('alice','alice@example.test',"
					+ "'synthetic-test-hash','es','alice') RETURNING id")) {
				result.next(); ownerId = result.getLong(1);
			}
			try (var result = statement.executeQuery("INSERT INTO " + schema + ".reservation_process"
					+ " (owner_user_id,professional_id,appointment_date,start_time,external_patient_id,created_by,"
					+ "process_status,hold_id,external_reservation_process_id,expires_at) VALUES (" + ownerId
					+ ",202,DATE '2030-01-03',TIME '18:00','user-" + ownerId + "','alice','HELD','hold','process',"
					+ "TIMESTAMPTZ '2026-11-20 12:02:00.123456Z') RETURNING id")) {
				result.next(); id = result.getLong(1);
			}
		}
		startContext();
	}

	private void startContext() {
		Map<String, Object> settings = new HashMap<>();
		settings.put(FinalUserJwtTestSupport.SECRET_PROPERTY, FinalUserJwtTestSupport.signingSecret());
		settings.put("spring.datasource.url", url);
		settings.put("spring.datasource.username", username);
		settings.put("spring.datasource.password", password);
		settings.put("spring.flyway.schemas", schema);
		settings.put("spring.flyway.default-schema", schema);
		settings.put("spring.flyway.create-schemas", false);
		settings.put("spring.jpa.properties.hibernate.default_schema", schema);
		settings.put("spring.jpa.hibernate.ddl-auto", "validate");
		settings.put("spring.jackson.serialization.indent-output", prettyHttpJson);
		context = new SpringApplicationBuilder(AppointmentReservationApplication.class)
				.initializers(CatedraIntegrationTestSupport::configure, CatalogIntegrationTestSupport::configure)
				.initializers(app -> app.getEnvironment().getPropertySources().addFirst(new MapPropertySource("reservationEventTest", settings)))
				.web(WebApplicationType.NONE).run();
		events = context.getBean(ReservationEventStore.class);
		store = context.getBean(ReservationProcessStore.class);
		processes = context.getBean(ReservationProcessJpaRepository.class);
		phones = context.getBean(PhoneSubmissionStore.class);
		actions = context.getBean(ProcessReservationActionUseCase.class);
		rejectedActions = context.getBean(RejectedCatedraActionStore.class);
	}

	@AfterEach
	void closeAndRemoveOnlyThisSchema(CapturedOutput output) throws Exception {
		try { if (context != null) { context.close(); } }
		finally {
			if (schema != null) {
				try (var connection = connection(); var statement = connection.createStatement()) {
					statement.execute("DROP SCHEMA " + schema + " CASCADE");
				}
			}
		}
		assertThat(output.getAll()).doesNotContain("00000001234", "00000005678", FinalUserJwtTestSupport.signingSecret(),
				"test-only-private-commit-diagnostic", "Failing row contains", "not-a-real-catedra-token");
	}

	@Test
	void asynchronousMigrationsPreserveOwnershipSelectionExpiryAndAuditOfTheExistingV4Row() {
		assertThat(context.getBean(Flyway.class).info().applied()).extracting(m -> m.getVersion().toString())
				.containsExactly("1", "2", "3", "4", "5", "6", "7", "8");
		assertThat(context.getBean(Flyway.class).migrate().migrationsExecuted).isZero();
		var process = process();
		assertThat(process.getOwner().getId()).isEqualTo(ownerId);
		assertThat(process.getExternalPatientId()).isEqualTo("user-" + ownerId);
		assertThat(process.getStatus()).isEqualTo(ReservationProcessStatus.HELD);
		assertThat(process.getVersion()).isZero();
		assertThat(process.getProfessionalId()).isEqualTo(202);
		assertThat(process.getCreatedBy()).isEqualTo("alice");
		assertThat(process.getCreatedDate()).isNotNull();
		assertThat(process.getExpiresAt()).isEqualTo(Instant.parse("2026-11-20T12:02:00.123456Z"));
		assertThat(process.getRequestEventId()).isNull();
		assertThat(process.getExternalReservationId()).isNull();
		assertThat(process.getOutcomeReason()).isNull();
	}

	@Test
	void receiptAndNanosecondEvidenceSurviveRestartAndApplyOnlyAfterACorrelatedClaimExists() {
		assertThat(events.receive(request()).state()).isEqualTo(State.PENDING);
		assertThat(events.applyPending("request").state()).isEqualTo(State.PENDING);
		context.close();
		prettyHttpJson = true;
		startContext();
		assertThat(events.findPending("process", 10)).containsExactly("request");
		claim();
		assertThat(events.applyPending("request").state()).isEqualTo(State.APPLIED);
		assertThat(process().getExpiresAt()).isEqualTo(EXPIRY.truncatedTo(ChronoUnit.MICROS));
		// Original nanosecond content remains identical, even after read/write and restart.
		assertThat(events.receive(request()).contentConflict()).isFalse();
	}

	@Test
	void aRequestCanConsolidateAnUncertainRestClaimWithoutChangingOwnerOrNames() {
		var claim = claim();
		events.receive(request());
		var receipt = events.applyPending("request");
		assertThat(receipt.state()).isEqualTo(State.APPLIED);
		assertThat(receipt.localProcessId()).isEqualTo(id);
		var process = process();
		assertThat(process.getStatus()).isEqualTo(ReservationProcessStatus.WAITING_FOR_PHONE);
		assertThat(process.getRequestEventId()).isEqualTo("request");
		assertThat(process.getOwner().getId()).isEqualTo(ownerId);
		assertThat(process.getPatientFirstName()).isEqualTo("Ana");
		assertThat(process.getLastModifiedBy()).isEqualTo("catedra-events");
		assertThat(process.getVersion()).isEqualTo(claim.version() + 1);
		assertThatThrownBy(() -> store.recordInitialConfirmation(id, claim.version(), owner(),
				new InitialReservationConfirmation("process", "Ana", "Perez", EXPIRY)))
				.isInstanceOf(ReservationProcessStateException.class);
		assertThat(process().getRequestEventId()).isEqualTo("request");
	}

	@Test
	void identicalRedeliveryDoesNotRepeatBusinessAuditOrVersion() {
		applyRequest();
		var before = process();
		assertThat(events.receive(request()).state()).isEqualTo(State.APPLIED);
		assertThat(events.applyPending("request").state()).isEqualTo(State.APPLIED);
		assertThat(process().getVersion()).isEqualTo(before.getVersion());
		assertThat(process().getLastModifiedDate()).isEqualTo(before.getLastModifiedDate());
	}

	@Test
	void incompatibleReuseOfAnAppliedIdPreservesTheOriginalReceiptAndProcess() {
		applyRequest();
		var before = process();
		String originalPayload = context.getBean(ReservationEventInboxJpaRepository.class).findById("request").orElseThrow().getPayload();
		var conflicting = new ReservationActionEvent("request", Signal.PHONE_REQUESTED, "process", "hold", null,
				TIME, EXPIRY.plusSeconds(60), null, null, null);
		var receipt = events.receive(conflicting);
		assertThat(receipt.state()).isEqualTo(State.APPLIED);
		assertThat(receipt.contentConflict()).isTrue();
		assertThat(events.applyPending("request").contentConflict()).isTrue();
		assertThat(process().getExpiresAt()).isEqualTo(before.getExpiresAt());
		assertThat(process().getVersion()).isEqualTo(before.getVersion());
		assertThat(context.getBean(ReservationEventInboxJpaRepository.class).findById("request").orElseThrow()
				.getPayload()).isEqualTo(originalPayload);
	}

	@Test
	void correlationUsesThePersistedOwnerAndCannotAffectAnotherUsersProcess() throws Exception {
		long otherId;
		try (var connection = connection(); var statement = connection.createStatement();
				var result = statement.executeQuery("INSERT INTO " + schema + ".app_user"
						+ " (login,email,password_hash,lang_key,created_by) VALUES ('bob','bob@example.test','synthetic-test-hash','es','bob') RETURNING id")) {
			result.next(); otherId = result.getLong(1);
		}
		var other = new AuthenticatedReservationUser(otherId, "bob");
		var held = store.createHeld(other, SELECTION,
				new ar.edu.um.prog2.appointmentreservation.reservation.application.model.AppointmentHold("bob-hold", "bob-process", EXPIRY));
		store.claimInitialConfirmation(held.id(), 0, other, new ConfirmationPatient("Bob", "Patient"), TIME);
		events.receive(new ReservationActionEvent("bob-request", Signal.PHONE_REQUESTED, "bob-process", "bob-hold", null,
				TIME, EXPIRY, null, null, null));
		assertThat(events.applyPending("bob-request").localProcessId()).isEqualTo(held.id());
		assertThat(processes.findByIdAndOwner_Id(held.id(), otherId).orElseThrow().getRequestEventId()).isEqualTo("bob-request");
		assertThat(processes.findByIdAndOwner_Id(held.id(), ownerId)).isEmpty();
		assertThat(process().getStatus()).isEqualTo(ReservationProcessStatus.HELD);
		assertThat(process().getVersion()).isZero();
	}

	@Test
	void concurrentContradictoryResultsCannotOverwriteEachOther() throws Exception {
		applyRequest();
		events.receive(confirmed("confirmed", 8251, "request"));
		events.receive(new ReservationActionEvent("expired", Signal.PROCESS_EXPIRED, "process", "hold", "request", TIME,
				null, null, null, "HOLD_EXPIRED"));
		long before = process().getVersion();
		var order = new java.util.concurrent.atomic.AtomicInteger();
		assertThat(concurrently(() -> events.applyPending(order.getAndIncrement() == 0 ? "confirmed" : "expired").state()))
				.containsExactlyInAnyOrder(State.APPLIED, State.CONFLICT);
		assertThat(process().getStatus()).isIn(ReservationProcessStatus.CONFIRMED, ReservationProcessStatus.EXPIRED);
		assertThat(process().getVersion()).isEqualTo(before + 1);
	}

	@Test
	void reservationIdentifiersRemainUniqueAndInboxReferencesOnlyExistingLocalProcesses() {
		applyRequest();
		events.receive(confirmed("confirmed", 8251, "request")); events.applyPending("confirmed");
		assertThatThrownBy(() -> execute("INSERT INTO " + schema + ".reservation_process"
				+ " (owner_user_id,professional_id,appointment_date,start_time,external_patient_id,created_by,process_status,"
				+ "hold_id,external_reservation_process_id,expires_at,patient_first_name,patient_last_name,request_event_id,external_reservation_id)"
				+ " SELECT owner_user_id,professional_id,appointment_date,start_time,external_patient_id,created_by,process_status,"
				+ "'other-hold','other-process',expires_at,patient_first_name,patient_last_name,'other-request',external_reservation_id"
				+ " FROM " + schema + ".reservation_process WHERE id=" + id))
				.isInstanceOfSatisfying(SQLException.class, error -> assertThat(error.getSQLState()).isEqualTo("23505"));
		assertThatThrownBy(() -> execute("UPDATE " + schema + ".reservation_event_inbox SET local_process_id="
				+ Long.MAX_VALUE + " WHERE event_id='request'"))
				.isInstanceOfSatisfying(SQLException.class, error -> assertThat(error.getSQLState()).isEqualTo("23503"));
		assertThat(process().getExternalReservationId()).isEqualTo(8251);
	}

	@Test
	void conflictingPendingContentIsDurableAndExcludedFromAutomaticContinuation() {
		claim();
		events.receive(request());
		events.receive(new ReservationActionEvent("request", Signal.PHONE_REQUESTED, "process", "hold", null,
				TIME.plusSeconds(1), EXPIRY, null, null, null));
		assertThat(events.findPending("process", 10)).isEmpty();
		assertThat(events.applyPending("request").contentConflict()).isTrue();
		assertThat(process().getStatus()).isEqualTo(ReservationProcessStatus.CONFIRMATION_PENDING);
	}

	@Test
	void anUnknownProcessIsKeptPendingUntilItsLocalCorrelationBecomesAvailable() {
		var event = new ReservationActionEvent("other-request", Signal.PHONE_REQUESTED, "other-process", "other-hold", null,
				TIME, EXPIRY, null, null, null);
		events.receive(event);
		assertThat(events.applyPending(event.eventId()).state()).isEqualTo(State.PENDING);
		var held = store.createHeld(owner(), SELECTION,
				new ar.edu.um.prog2.appointmentreservation.reservation.application.model.AppointmentHold("other-hold", "other-process", EXPIRY));
		store.claimInitialConfirmation(held.id(), 0, owner(), new ConfirmationPatient("Ana", "Perez"), TIME);
		assertThat(events.applyPending(event.eventId()).localProcessId()).isEqualTo(held.id());
		assertThat(process().getStatus()).isEqualTo(ReservationProcessStatus.HELD);
	}

	@Test
	void sameIdentityCanBeReceivedAndAppliedConcurrentlyWithOnlyOneEffect() throws Exception {
		claim();
		assertThat(concurrently(() -> events.receive(request()).state())).containsOnly(State.PENDING);
		assertThat(context.getBean(ReservationEventInboxJpaRepository.class).count()).isEqualTo(1);
		assertThat(concurrently(() -> events.applyPending("request").state())).containsOnly(State.APPLIED);
		assertThat(process().getVersion()).isEqualTo(2);
	}

	@Test
	void aFailedCommitRollsBackProcessAndReceiptAndAllowsTheSameEventToBeRetried() throws Exception {
		claim();
		events.receive(request());
		execute("CREATE FUNCTION " + schema + ".reject_event_commit() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN "
				+ "IF NEW.handling_status = 'APPLIED' THEN RAISE EXCEPTION USING MESSAGE = 'test-only-private-commit-diagnostic', "
				+ "ERRCODE = '23514'; END IF; RETURN NEW; END $$");
		execute("CREATE CONSTRAINT TRIGGER test_reject_event_commit AFTER UPDATE ON " + schema
				+ ".reservation_event_inbox DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION " + schema + ".reject_event_commit()");
		assertThatThrownBy(() -> events.applyPending("request")).isInstanceOf(ReservationEventStorageException.class)
				.hasNoCause().hasMessageNotContaining("test-only-private-commit-diagnostic");
		assertThat(process().getStatus()).isEqualTo(ReservationProcessStatus.CONFIRMATION_PENDING);
		assertThat(process().getVersion()).isEqualTo(1);
		assertThat(process().getRequestEventId()).isNull();
		assertThat(events.findPending("process", 10)).containsExactly("request");
		execute("DROP TRIGGER test_reject_event_commit ON " + schema + ".reservation_event_inbox");
		assertThat(events.applyPending("request").state()).isEqualTo(State.APPLIED);
	}

	@Test
	void aFinalResultCanWaitForItsRequestThenCommitTheReservationWithoutDuplicateEffects() {
		claim();
		events.receive(confirmed("confirmed", 8251, "request"));
		assertThat(events.applyPending("confirmed").state()).isEqualTo(State.PENDING);
		events.receive(request());
		events.applyPending("request");
		assertThat(events.applyPending("confirmed").state()).isEqualTo(State.APPLIED);
		assertThat(process().getStatus()).isEqualTo(ReservationProcessStatus.CONFIRMED);
		assertThat(process().getExternalReservationId()).isEqualTo(8251);
		long version = process().getVersion();
		events.receive(confirmed("confirmed-again", 8251, "request"));
		assertThat(events.applyPending("confirmed-again").state()).isEqualTo(State.IGNORED);
		assertThat(process().getVersion()).isEqualTo(version);
	}

	@Test
	void terminalContradictionsAreDurableAndNeverOverwriteTheConfirmedReservation() {
		applyRequest();
		events.receive(confirmed("confirmed", 8251, "request")); events.applyPending("confirmed");
		long version = process().getVersion();
		events.receive(new ReservationActionEvent("late-expiry", Signal.PROCESS_EXPIRED, "process", "hold", "request",
				TIME, null, null, null, "HOLD_EXPIRED"));
		assertThat(events.applyPending("late-expiry").state()).isEqualTo(State.CONFLICT);
		events.receive(confirmed("other-reservation", 8252, "request"));
		assertThat(events.applyPending("other-reservation").state()).isEqualTo(State.CONFLICT);
		assertThat(process().getVersion()).isEqualTo(version);
		assertThat(process().getExternalReservationId()).isEqualTo(8251);
	}

	@Test
	void mismatchedHoldsRequestsAndUnknownReasonsCannotChangeTheOwnedProcess() {
		applyRequest();
		long version = process().getVersion();
		events.receive(new ReservationActionEvent("wrong-hold", Signal.PHONE_REQUESTED, "process", "other-hold", null,
				TIME, EXPIRY, null, null, null));
		assertThat(events.applyPending("wrong-hold").state()).isEqualTo(State.CONFLICT);
		events.receive(confirmed("wrong-request", 8251, "another-request"));
		assertThat(events.applyPending("wrong-request").state()).isEqualTo(State.CONFLICT);
		events.receive(new ReservationActionEvent("unknown-reason", Signal.PROCESS_INVALID, "process", "hold", "request",
				TIME, null, null, null, "UNRECOGNIZED_REASON"));
		assertThat(events.applyPending("unknown-reason").state()).isEqualTo(State.CONFLICT);
		assertThat(process().getVersion()).isEqualTo(version);
		assertThat(process().getOwner().getId()).isEqualTo(ownerId);
	}

	@Test
	void aPhoneRejectionIsCorrectableAndPreservesTheOriginalRequestAndDeadline() throws Exception {
		applyRequest();
		phones.prepare(owner(), new NewPhoneSubmission(id, "phone", "00000001234", "test-project"), TIME);
		events.receive(new ReservationActionEvent("rejected", Signal.PHONE_REJECTED, "process", "hold", "request", TIME,
				EXPIRY, null, null, "INVALID_PHONE_NUMBER"));
		assertThat(events.applyPending("rejected").state()).isEqualTo(State.APPLIED);
		assertThat(process().getStatus()).isEqualTo(ReservationProcessStatus.WAITING_FOR_PHONE);
		assertThat(process().getOutcomeReason()).isEqualTo("INVALID_PHONE_NUMBER");
		assertThat(process().getRequestEventId()).isEqualTo("request");
		assertThat(process().getExpiresAt()).isEqualTo(EXPIRY.truncatedTo(ChronoUnit.MICROS));
	}

	@ParameterizedTest
	@ValueSource(booleans = {true, false})
	void expiryAndInvalidityPersistAuthoritativeTerminalReasons(boolean expired) {
		applyRequest();
		var event = new ReservationActionEvent("final", expired ? Signal.PROCESS_EXPIRED : Signal.PROCESS_INVALID,
				"process", "hold", "request", TIME, null, null, null, expired ? "HOLD_EXPIRED" : "REQUEST_EVENT_MISMATCH");
		events.receive(event);
		assertThat(events.applyPending("final").state()).isEqualTo(State.APPLIED);
		assertThat(process().getStatus()).isEqualTo(expired ? ReservationProcessStatus.EXPIRED : ReservationProcessStatus.INVALID);
		assertThat(process().getOutcomeReason()).isEqualTo(event.reason());
		assertThat(process().getExpiresAt()).isEqualTo(EXPIRY.truncatedTo(ChronoUnit.MICROS));
	}

	@Test
	void schemaRejectsIncompleteFinalStateAndIncompleteInboxHandling() {
		assertThatThrownBy(() -> execute("UPDATE " + schema + ".reservation_process SET process_status='CONFIRMED' WHERE id=" + id))
				.isInstanceOfSatisfying(SQLException.class, error -> assertThat(error.getSQLState()).isEqualTo("23514"));
		assertThatThrownBy(() -> execute("INSERT INTO " + schema + ".reservation_event_inbox"
				+ " (event_id,external_process_id,payload,fingerprint,handling_status) VALUES ('invalid','process','{}','"
				+ "a".repeat(64) + "','APPLIED')"))
				.isInstanceOfSatisfying(SQLException.class, error -> assertThat(error.getSQLState()).isEqualTo("23514"));
	}

	@Test
	void pendingDiscoveryIsBoundedAndDoesNotClaimEventsApplied() {
		claim();
		for (String key : List.of("first", "second", "third")) {
			events.receive(new ReservationActionEvent(key, Signal.PHONE_REQUESTED, "process", "hold", null, TIME, EXPIRY, null, null, null));
		}
		assertThat(events.findPending("process", 2)).hasSize(2);
		assertThat(process().getStatus()).isEqualTo(ReservationProcessStatus.CONFIRMATION_PENDING);
		assertThatThrownBy(() -> events.findPending("process", 0)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> events.findPending("process", 101)).isInstanceOf(IllegalArgumentException.class);
	}


	@Test
	void phoneIntentAndProcessCommitTogetherAndRetainTheirPayloadAcrossRestart() {
		applyRequest();
		var intent = prepare("phone", "00000001234");
		assertThat(intent.state()).isEqualTo(PhoneSubmissionIntent.State.PENDING);
		assertThat(intent.requestEventId()).isEqualTo("request");
		assertThat(intent.holdId()).isEqualTo("hold");
		assertThat(intent.externalProcessId()).isEqualTo("process");
		assertThat(intent.technicalGroupId()).isEqualTo("test-project");
		assertThat(intent.occurredAt()).isEqualTo(TIME.truncatedTo(ChronoUnit.MICROS));
		assertThat(process().getStatus()).isEqualTo(ReservationProcessStatus.PHONE_SUBMISSION_PENDING);
		assertThat(process().getCurrentPhoneSubmissionEventId()).isEqualTo("phone");
		context.close(); startContext();
		assertThat(phones.findOwned(id, owner())).contains(intent);
		assertThat(phones.findPending(TIME, 10)).containsExactly("phone");
		assertThat(phones.findPublishable("phone", TIME)).contains(intent);
		assertThat(prepare("new-candidate", "00000001234")).isEqualTo(intent);
	}

	@Test
	void concurrentPreparationOfTheSamePhoneCreatesOnlyOneStableIntent() throws Exception {
		applyRequest();
		var candidate = new java.util.concurrent.atomic.AtomicInteger();
		var intents = concurrently(() -> prepare("candidate-" + candidate.incrementAndGet(), "00000001234"));
		assertThat(intents.getFirst().eventId()).isEqualTo(intents.getLast().eventId());
		assertThat(query("SELECT count(*) FROM " + schema + ".reservation_phone_submission")).isEqualTo(1);
		assertThat(process().getVersion()).isEqualTo(3);
	}

	@Test
	void foreignAbsentUnrequestedAndExpiredProcessesCannotCreateIntentions() throws Exception {
		assertThatThrownBy(() -> prepare("unrequested", "00000001234"))
				.isInstanceOfSatisfying(PhoneSubmissionStateException.class,
						error -> assertThat(error.reason()).isEqualTo(PhoneSubmissionStateException.Reason.CONFLICT));
		applyRequest();
		var other = new AuthenticatedReservationUser(Long.MAX_VALUE, "another-user");
		assertThat(phones.findOwned(id, other)).isEmpty();
		assertThatThrownBy(() -> phones.prepare(other, new NewPhoneSubmission(id, "foreign", "00000001234", "test-project"), TIME))
				.isInstanceOfSatisfying(PhoneSubmissionStateException.class,
						error -> assertThat(error.reason()).isEqualTo(PhoneSubmissionStateException.Reason.NOT_FOUND));
		assertThatThrownBy(() -> phones.prepare(owner(), new NewPhoneSubmission(Long.MAX_VALUE, "absent", "00000001234", "test-project"), TIME))
				.isInstanceOfSatisfying(PhoneSubmissionStateException.class,
						error -> assertThat(error.reason()).isEqualTo(PhoneSubmissionStateException.Reason.NOT_FOUND));
		assertThatThrownBy(() -> phones.prepare(owner(), new NewPhoneSubmission(id, "expired", "00000001234", "test-project"), EXPIRY))
				.isInstanceOfSatisfying(PhoneSubmissionStateException.class,
						error -> assertThat(error.reason()).isEqualTo(PhoneSubmissionStateException.Reason.EXPIRED));
		assertThat(query("SELECT count(*) FROM " + schema + ".reservation_phone_submission")).isZero();
	}

	@Test
	void replayAfterDeadlineKeepsItsIdentityWithoutMakingItPublishableOrRenewingExpiry() {
		applyRequest();
		var original = prepare("phone", "00000001234");
		var repeat = phones.prepare(owner(), new NewPhoneSubmission(id, "another-id", "00000001234", "test-project"), EXPIRY.plusSeconds(1));
		assertThat(repeat).isEqualTo(original);
		assertThat(phones.findPublishable("phone", EXPIRY)).isEmpty();
		assertThat(phones.findPending(EXPIRY, 10)).isEmpty();
		assertThat(process().getExpiresAt()).isEqualTo(EXPIRY.truncatedTo(ChronoUnit.MICROS));
	}

	@Test
	void anotherPhoneOrTechnicalIdentityCannotReplaceAnInFlightIntent() {
		applyRequest(); prepare("phone", "00000001234");
		assertThatThrownBy(() -> prepare("other", "00000005678"))
				.isInstanceOfSatisfying(PhoneSubmissionStateException.class,
						error -> assertThat(error.reason()).isEqualTo(PhoneSubmissionStateException.Reason.CONFLICT));
		assertThatThrownBy(() -> phones.prepare(owner(), new NewPhoneSubmission(id, "other", "00000001234", "another-project"), TIME))
				.isInstanceOf(PhoneSubmissionStateException.class);
		assertThat(phones.findOwned(id, owner()).orElseThrow().normalizedPhone()).isEqualTo("00000001234");
	}

	@Test
	void brokerAcknowledgmentMeansPublishedAndIsIdempotentButDoesNotMeanConfirmed() {
		applyRequest(); prepare("phone", "00000001234");
		var published = phones.recordPublished("phone", TIME.plusSeconds(1));
		assertThat(published.state()).isEqualTo(PhoneSubmissionIntent.State.PUBLISHED);
		assertThat(process().getStatus()).isEqualTo(ReservationProcessStatus.WAITING_FOR_CONFIRMATION);
		assertThat(process().getExternalReservationId()).isNull();
		assertThat(phones.findPending(TIME, 10)).isEmpty();
		long version = process().getVersion();
		assertThat(phones.recordPublished("phone", TIME.plusSeconds(2))).isEqualTo(published);
		assertThat(process().getVersion()).isEqualTo(version);
	}

	@Test
	void rejectionClosesTheOldIntentAndCorrectionGetsANewIdWhileLateAckCannotAdvanceIt() {
		applyRequest(); prepare("phone", "00000001234");
		events.receive(rejection()); events.applyPending("rejected");
		assertThat(phones.findOwned(id, owner()).orElseThrow().state()).isEqualTo(PhoneSubmissionIntent.State.CLOSED);
		assertThat(prepare("same-phone-candidate", "00000001234").eventId()).isEqualTo("phone");
		assertThatThrownBy(() -> prepare("phone", "00000005678")).isInstanceOf(PhoneSubmissionStateException.class);
		var corrected = prepare("correction", "00000005678");
		assertThat(corrected.requestEventId()).isEqualTo("request");
		long version = process().getVersion();
		assertThat(phones.recordPublished("phone", TIME.plusSeconds(1)).state()).isEqualTo(PhoneSubmissionIntent.State.CLOSED);
		assertThat(process().getStatus()).isEqualTo(ReservationProcessStatus.PHONE_SUBMISSION_PENDING);
		assertThat(process().getCurrentPhoneSubmissionEventId()).isEqualTo("correction");
		assertThat(process().getVersion()).isEqualTo(version);
		assertThat(phones.findPending(TIME, 10)).containsExactly("correction");
	}

	@Test
	void concurrentRejectionAndBrokerAckAlwaysLeaveACorrectableClosedIntent() throws Exception {
		applyRequest(); prepare("phone", "00000001234");
		events.receive(rejection());
		var start = new CountDownLatch(1);
		try (var pool = Executors.newFixedThreadPool(2)) {
			var published = pool.submit(() -> { assertThat(start.await(5, TimeUnit.SECONDS)).isTrue(); return phones.recordPublished("phone", TIME); });
			var rejected = pool.submit(() -> { assertThat(start.await(5, TimeUnit.SECONDS)).isTrue(); return events.applyPending("rejected"); });
			start.countDown(); published.get(15, TimeUnit.SECONDS); rejected.get(15, TimeUnit.SECONDS);
		}
		assertThat(process().getStatus()).isEqualTo(ReservationProcessStatus.WAITING_FOR_PHONE);
		assertThat(phones.findOwned(id, owner()).orElseThrow().state()).isEqualTo(PhoneSubmissionIntent.State.CLOSED);
	}

	@ParameterizedTest
	@ValueSource(strings = {"confirmed", "expired", "invalid"})
	void authoritativeFinalResultsCloseTheIntentAndBlockAnotherSubmission(String kind) {
		applyRequest(); prepare("phone", "00000001234");
		var result = switch (kind) {
			case "confirmed" -> confirmed("final", 8251, "request");
			case "expired" -> new ReservationActionEvent("final", Signal.PROCESS_EXPIRED, "process", "hold", "request", TIME,
					null, null, null, "HOLD_EXPIRED");
			default -> new ReservationActionEvent("final", Signal.PROCESS_INVALID, "process", "hold", "request", TIME,
					null, null, null, "REQUEST_EVENT_MISMATCH");
		};
		events.receive(result); events.applyPending("final");
		assertThat(phones.findOwned(id, owner()).orElseThrow().state()).isEqualTo(PhoneSubmissionIntent.State.CLOSED);
		long version = process().getVersion();
		phones.recordPublished("phone", TIME.plusSeconds(1));
		assertThat(process().getVersion()).isEqualTo(version);
		assertThat(process().getStatus().isTerminal()).isTrue();
		assertThatThrownBy(() -> prepare("new", "00000005678")).isInstanceOf(PhoneSubmissionStateException.class);
	}

	@Test
	void disputedRequestIsNotEligibleForPublicationEvenIfAnIntentWasAlreadyPrepared() {
		applyRequest(); prepare("phone", "00000001234");
		events.receive(new ReservationActionEvent("request", Signal.PHONE_REQUESTED, "process", "hold", null,
				TIME, EXPIRY.plusSeconds(1), null, null, null));
		assertThat(phones.findPending(TIME, 10)).isEmpty();
		assertThat(phones.findPublishable("phone", TIME)).isEmpty();
	}

	@Test
	void failedPreparationCommitRollsBackBothIntentAndProcessWithoutLeakingPhone() throws Exception {
		applyRequest(); long version = process().getVersion();
		execute("CREATE FUNCTION " + schema + ".reject_phone_commit() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN "
				+ "RAISE EXCEPTION USING MESSAGE='test-only-private-commit-diagnostic',ERRCODE='23514'; RETURN NEW; END $$");
		execute("CREATE CONSTRAINT TRIGGER test_reject_phone_commit AFTER INSERT ON " + schema
				+ ".reservation_phone_submission DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION " + schema + ".reject_phone_commit()");
		assertThatThrownBy(() -> prepare("phone", "00000001234"))
				.isInstanceOfSatisfying(PhoneSubmissionStateException.class,
						error -> assertThat(error.reason()).isEqualTo(PhoneSubmissionStateException.Reason.STORAGE_FAILURE))
				.hasNoCause().hasMessageNotContaining("00000001234");
		assertThat(process().getVersion()).isEqualTo(version);
		assertThat(process().getCurrentPhoneSubmissionEventId()).isNull();
		assertThat(query("SELECT count(*) FROM " + schema + ".reservation_phone_submission")).isZero();
		execute("DROP TRIGGER test_reject_phone_commit ON " + schema + ".reservation_phone_submission");
		assertThat(prepare("phone", "00000001234").state()).isEqualTo(PhoneSubmissionIntent.State.PENDING);
	}

	@Test
	void failedClosureCommitRollsBackResultInboxAndIntentTogether() throws Exception {
		applyRequest(); prepare("phone", "00000001234");
		events.receive(confirmed("final", 8251, "request"));
		execute("CREATE FUNCTION " + schema + ".reject_phone_closure() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN "
				+ "IF NEW.publication_state='CLOSED' THEN RAISE EXCEPTION USING MESSAGE='test-only-private-commit-diagnostic',"
				+ "ERRCODE='23514'; END IF; RETURN NEW; END $$");
		execute("CREATE CONSTRAINT TRIGGER test_reject_phone_closure AFTER UPDATE ON " + schema
				+ ".reservation_phone_submission DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION " + schema + ".reject_phone_closure()");
		assertThatThrownBy(() -> events.applyPending("final")).isInstanceOf(ReservationEventStorageException.class).hasNoCause();
		assertThat(process().getStatus()).isEqualTo(ReservationProcessStatus.PHONE_SUBMISSION_PENDING);
		assertThat(process().getExternalReservationId()).isNull();
		assertThat(phones.findOwned(id, owner()).orElseThrow().state()).isEqualTo(PhoneSubmissionIntent.State.PENDING);
		assertThat(events.findPending("process", 10)).containsExactly("final");
		execute("DROP TRIGGER test_reject_phone_closure ON " + schema + ".reservation_phone_submission");
		assertThat(events.applyPending("final").state()).isEqualTo(State.APPLIED);
	}

	@Test
	void databaseRequiresCurrentPointerToBelongToTheSameProcessAndOnlyOneActiveIntent() throws Exception {
		applyRequest(); prepare("phone", "00000001234");
		var other = store.createHeld(owner(), SELECTION,
				new ar.edu.um.prog2.appointmentreservation.reservation.application.model.AppointmentHold("other-hold", "other-process", EXPIRY));
		assertThatThrownBy(() -> execute("UPDATE " + schema + ".reservation_process SET current_phone_submission_event_id='phone' WHERE id=" + other.id()))
				.isInstanceOfSatisfying(SQLException.class, error -> assertThat(error.getSQLState()).isEqualTo("23503"));
		assertThatThrownBy(() -> execute("INSERT INTO " + schema + ".reservation_phone_submission"
				+ " (event_id,local_process_id,request_event_id,external_process_id,hold_id,technical_group_id,normalized_phone,occurred_at)"
				+ " SELECT 'another-active',local_process_id,request_event_id,external_process_id,hold_id,technical_group_id,normalized_phone,occurred_at"
				+ " FROM " + schema + ".reservation_phone_submission WHERE event_id='phone'"))
				.isInstanceOfSatisfying(SQLException.class, error -> assertThat(error.getSQLState()).isEqualTo("23505"));
	}

	@Test
	void failedPublicationCommitKeepsTheOriginalIntentPendingForASafeSameIdRetry() throws Exception {
		applyRequest(); var original = prepare("phone", "00000001234");
		long version = process().getVersion();
		execute("CREATE FUNCTION " + schema + ".reject_publication() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN "
				+ "IF NEW.publication_state='PUBLISHED' THEN RAISE EXCEPTION USING MESSAGE='test-only-private-commit-diagnostic',"
				+ "ERRCODE='23514'; END IF; RETURN NEW; END $$");
		execute("CREATE CONSTRAINT TRIGGER test_reject_publication AFTER UPDATE ON " + schema
				+ ".reservation_phone_submission DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION " + schema + ".reject_publication()");
		assertThatThrownBy(() -> phones.recordPublished("phone", TIME.plusSeconds(1)))
				.isInstanceOfSatisfying(PhoneSubmissionStateException.class,
						error -> assertThat(error.reason()).isEqualTo(PhoneSubmissionStateException.Reason.STORAGE_FAILURE)).hasNoCause();
		assertThat(phones.findPublishable("phone", TIME)).contains(original);
		assertThat(process().getVersion()).isEqualTo(version);
		assertThat(process().getStatus()).isEqualTo(ReservationProcessStatus.PHONE_SUBMISSION_PENDING);
		execute("DROP TRIGGER test_reject_publication ON " + schema + ".reservation_phone_submission");
		assertThat(phones.recordPublished("phone", TIME.plusSeconds(1)).state()).isEqualTo(PhoneSubmissionIntent.State.PUBLISHED);
	}

	@ParameterizedTest
	@CsvSource({"AdditionalInformationRequested,WAITING_FOR_PHONE", "AppointmentConfirmed,CONFIRMED",
			"AdditionalInformationRejected,WAITING_FOR_PHONE", "AppointmentProcessExpired,EXPIRED", "AppointmentProcessInvalid,INVALID"})
	void decodedActionsReachTheOwnedProcessThroughTheApplicationUseCase(String type, ReservationProcessStatus expected) {
		claim();
		if (!type.equals("AdditionalInformationRequested")) {
			actions.process(decodeAction("AdditionalInformationRequested"));
		}
		if (type.equals("AdditionalInformationRejected")) { prepare("phone", "00000001234"); }
		var receipt = actions.process(decodeAction(type));
		assertThat(receipt.state()).isEqualTo(State.APPLIED);
		assertThat(receipt.localProcessId()).isEqualTo(id);
		assertThat(process().getStatus()).isEqualTo(expected);
		assertThat(process().getOwner().getId()).isEqualTo(ownerId);
		assertThat(process().getRequestEventId()).isEqualTo("request");
		assertThat(process().getPatientFirstName()).isEqualTo("Ana");
		assertThat(process().getPatientLastName()).isEqualTo("Perez");
		assertThat(process().getExpiresAt()).isEqualTo(EXPIRY.truncatedTo(ChronoUnit.MICROS));
		assertThat(process().getExternalReservationId()).isEqualTo(type.equals("AppointmentConfirmed") ? 8251L : null);
	}

	@Test
	void processingNewRequestContinuesAnEarlierResultWithoutWaitingForRedelivery() {
		claim();
		var confirmation = decodeAction("AppointmentConfirmed");
		assertThat(actions.process(confirmation).state()).isEqualTo(State.PENDING);
		assertThat(actions.process(decodeAction("AdditionalInformationRequested")).state()).isEqualTo(State.APPLIED);
		assertThat(process().getStatus()).isEqualTo(ReservationProcessStatus.CONFIRMED);
		assertThat(process().getExternalReservationId()).isEqualTo(8251L);
		assertThat(events.findPending("process", 10)).isEmpty();
		long version = process().getVersion();
		assertThat(actions.process(confirmation).state()).isEqualTo(State.APPLIED);
		assertThat(process().getVersion()).isEqualTo(version);
	}

	@Test
	void theApplicationCanResumeDurableEarlyEvidenceAfterRestartAndRestClaim() {
		assertThat(actions.process(decodeAction("AdditionalInformationRequested")).state()).isEqualTo(State.PENDING);
		context.close(); startContext();
		claim();
		assertThat(actions.resumePending("process", 1)).singleElement().satisfies(receipt ->
				assertThat(receipt.state()).isEqualTo(State.APPLIED));
		assertThat(process().getStatus()).isEqualTo(ReservationProcessStatus.WAITING_FOR_PHONE);
		assertThat(process().getOwner().getId()).isEqualTo(ownerId);
	}

	@Test
	void incompatibleWireRedeliveryCannotReplaceAppliedRequestOrItsDeadline() {
		claim();
		actions.process(decodeAction("AdditionalInformationRequested"));
		var before = process();
		var wire = wireAction("AdditionalInformationRequested").put("expiresAt", EXPIRY.plusSeconds(60).toString());
		var result = actions.process(new CatedraAppointmentActionDecoder("test-project").decode("process", wire.toString()));
		assertThat(result.contentConflict()).isTrue();
		assertThat(process().getVersion()).isEqualTo(before.getVersion());
		assertThat(process().getExpiresAt()).isEqualTo(before.getExpiresAt());
	}

	@Test
	void failedEffectCommitLeavesReceivedEvidencePendingAndAReplayCanCompleteIt() throws Exception {
		claim();
		execute("CREATE FUNCTION " + schema + ".reject_action_commit() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN "
				+ "IF NEW.handling_status='APPLIED' THEN RAISE EXCEPTION USING MESSAGE='test-only-private-commit-diagnostic',"
				+ "ERRCODE='23514'; END IF; RETURN NEW; END $$");
		execute("CREATE CONSTRAINT TRIGGER test_reject_action_commit AFTER UPDATE ON " + schema
				+ ".reservation_event_inbox DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION " + schema + ".reject_action_commit()");
		var request = decodeAction("AdditionalInformationRequested");
		assertThatThrownBy(() -> actions.process(request)).isInstanceOf(ReservationEventStorageException.class).hasNoCause();
		assertThat(process().getStatus()).isEqualTo(ReservationProcessStatus.CONFIRMATION_PENDING);
		assertThat(events.findPending("process", 10)).containsExactly("request");
		execute("DROP TRIGGER test_reject_action_commit ON " + schema + ".reservation_event_inbox");
		assertThat(actions.process(request).state()).isEqualTo(State.APPLIED);
		assertThat(process().getStatus()).isEqualTo(ReservationProcessStatus.WAITING_FOR_PHONE);
	}

	@ParameterizedTest
	@EnumSource(Reason.class)
	void technicalRejectionReasonsAreDurableWithoutBecomingAppliedBusinessEvents(Reason reason) throws Exception {
		rejectedActions.record(ACTIONS_TOPIC, 0, 42, "private-key", REJECTED_PAYLOAD, reason);
		assertThat(query("SELECT count(*) FROM " + schema + ".catedra_action_rejection WHERE rejection_reason='" + reason.name() + "'"))
				.isEqualTo(1);
		assertThat(query("SELECT count(*) FROM " + schema + ".reservation_event_inbox")).isZero();
		assertThat(process().getStatus()).isEqualTo(ReservationProcessStatus.HELD);
		assertThat(process().getVersion()).isZero();
		assertThat(rejectionSnapshot()).doesNotContain("private-key", "not-a-real-catedra-token", "00000001234");
		assertThat(query("SELECT count(*) FROM information_schema.columns WHERE table_schema='" + schema
				+ "' AND table_name='catedra_action_rejection'")).isEqualTo(6);
		assertThat(query("SELECT count(*) FROM information_schema.columns WHERE table_schema='" + schema
				+ "' AND table_name='catedra_action_rejection' AND column_name IN ('topic','partition_id','record_offset',"
				+ "'message_fingerprint','rejection_reason','received_at')")).isEqualTo(6);
	}

	@Test
	void malformedWireInputCanReceiveASanitizedDurableReceiptThatSurvivesRestart() throws Exception {
		var failure = catchThrowableOfType(InvalidCatedraActionException.class,
				() -> new CatedraAppointmentActionDecoder("test-project").decode("private-key", REJECTED_PAYLOAD));
		rejectedActions.record(ACTIONS_TOPIC, 0, 42, "private-key", REJECTED_PAYLOAD, failure.reason());
		String original = rejectionSnapshot();
		context.close(); startContext();
		rejectedActions.record(ACTIONS_TOPIC, 0, 42, "private-key", REJECTED_PAYLOAD, failure.reason());
		assertThat(rejectionSnapshot()).isEqualTo(original);
		assertThat(query("SELECT count(*) FROM " + schema + ".catedra_action_rejection")).isEqualTo(1);
	}

	@Test
	void concurrentIdenticalRejectedDeliveriesCreateOnlyOneReceipt() throws Exception {
		assertThat(concurrently(() -> {
			rejectedActions.record(ACTIONS_TOPIC, 0, 42, null, REJECTED_PAYLOAD, Reason.INVALID_PAYLOAD);
			return true;
		})).containsOnly(true);
		assertThat(query("SELECT count(*) FROM " + schema + ".catedra_action_rejection")).isEqualTo(1);
	}

	@Test
	void rejectionCommitIsIndependentOfAnAmbientTransactionThatLaterRollsBack() throws Exception {
		new TransactionTemplate(context.getBean(PlatformTransactionManager.class)).executeWithoutResult(transaction -> {
			rejectedActions.record(ACTIONS_TOPIC, 0, 42, null, null, Reason.INVALID_PAYLOAD);
			transaction.setRollbackOnly();
		});
		assertThat(query("SELECT count(*) FROM " + schema + ".catedra_action_rejection")).isEqualTo(1);
	}

	@Test
	void failedRejectionCommitRollsBackTheReceiptWithoutLeakingThePayloadAndAllowsRedelivery() throws Exception {
		execute("CREATE FUNCTION " + schema + ".reject_technical_receipt() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN "
				+ "RAISE EXCEPTION USING MESSAGE='test-only-private-commit-diagnostic',ERRCODE='23514'; RETURN NEW; END $$");
		execute("CREATE CONSTRAINT TRIGGER test_reject_technical_receipt AFTER INSERT ON " + schema
				+ ".catedra_action_rejection DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION " + schema + ".reject_technical_receipt()");
		assertThatThrownBy(() -> rejectedActions.record(ACTIONS_TOPIC, 0, 42, "private-key", REJECTED_PAYLOAD, Reason.INVALID_PAYLOAD))
				.isInstanceOf(CatedraActionStorageException.class).hasNoCause()
				.hasMessage("Catedra action rejection could not be durably recorded");
		assertThat(query("SELECT count(*) FROM " + schema + ".catedra_action_rejection")).isZero();
		execute("DROP TRIGGER test_reject_technical_receipt ON " + schema + ".catedra_action_rejection");
		rejectedActions.record(ACTIONS_TOPIC, 0, 42, "private-key", REJECTED_PAYLOAD, Reason.INVALID_PAYLOAD);
		assertThat(query("SELECT count(*) FROM " + schema + ".catedra_action_rejection")).isEqualTo(1);
	}

	@Test
	void incompatibleContentOrReasonCannotOverwriteTheOriginalDeliveryReceipt() throws Exception {
		rejectedActions.record(ACTIONS_TOPIC, 0, 42, "ab", "c", Reason.INVALID_PAYLOAD);
		String original = rejectionSnapshot();
		assertThatThrownBy(() -> rejectedActions.record(ACTIONS_TOPIC, 0, 42, "a", "bc", Reason.INVALID_PAYLOAD))
				.isInstanceOf(CatedraActionStorageException.class).hasNoCause();
		assertThatThrownBy(() -> rejectedActions.record(ACTIONS_TOPIC, 0, 42, "ab", "different", Reason.INVALID_PAYLOAD))
				.isInstanceOf(CatedraActionStorageException.class).hasNoCause();
		assertThatThrownBy(() -> rejectedActions.record(ACTIONS_TOPIC, 0, 42, "ab", "c", Reason.KEY_MISMATCH))
				.isInstanceOf(CatedraActionStorageException.class).hasNoCause();
		assertThat(rejectionSnapshot()).isEqualTo(original);
	}

	@Test
	void nullAndEmptyBodiesAndKeysHaveDistinctFingerprintsButOtherCoordinatesRemainIndependent() throws Exception {
		rejectedActions.record(ACTIONS_TOPIC, 0, 42, null, null, Reason.INVALID_PAYLOAD);
		assertThatThrownBy(() -> rejectedActions.record(ACTIONS_TOPIC, 0, 42, "", null, Reason.INVALID_PAYLOAD))
				.isInstanceOf(CatedraActionStorageException.class);
		assertThatThrownBy(() -> rejectedActions.record(ACTIONS_TOPIC, 0, 42, null, "", Reason.INVALID_PAYLOAD))
				.isInstanceOf(CatedraActionStorageException.class);
		rejectedActions.record(ACTIONS_TOPIC, 1, 42, null, null, Reason.INVALID_PAYLOAD);
		rejectedActions.record(ACTIONS_TOPIC, 0, 43, null, null, Reason.INVALID_PAYLOAD);
		assertThat(query("SELECT count(*) FROM " + schema + ".catedra_action_rejection")).isEqualTo(3);
	}

	@Test
	void rejectsOtherTopicsAndInvalidCoordinatesBeforeWritingTechnicalReceipts() throws Exception {
		for (String topic : List.of("test.appointment.phone", "test.catalog", "")) {
			assertThatThrownBy(() -> rejectedActions.record(topic, 0, 42, null, null, Reason.INVALID_PAYLOAD))
					.isInstanceOf(IllegalArgumentException.class);
		}
		assertThatThrownBy(() -> rejectedActions.record(null, 0, 42, null, null, Reason.INVALID_PAYLOAD)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> rejectedActions.record(ACTIONS_TOPIC, -1, 42, null, null, Reason.INVALID_PAYLOAD)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> rejectedActions.record(ACTIONS_TOPIC, 0, -1, null, null, Reason.INVALID_PAYLOAD)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> rejectedActions.record(ACTIONS_TOPIC, 0, 42, null, null, null)).isInstanceOf(IllegalArgumentException.class);
		assertThat(query("SELECT count(*) FROM " + schema + ".catedra_action_rejection")).isZero();
	}

	private String rejectionSnapshot() throws SQLException {
		try (var connection = connection(); var statement = connection.createStatement();
				var result = statement.executeQuery("SELECT row_to_json(r)::text FROM " + schema + ".catedra_action_rejection r")) {
			assertThat(result.next()).isTrue();
			return result.getString(1);
		}
	}

	private static ReservationActionEvent decodeAction(String type) {
		return new CatedraAppointmentActionDecoder("test-project").decode("process", wireAction(type).toString());
	}

	private static ObjectNode wireAction(String type) {
		var input = new JsonMapper().createObjectNode();
		input.put("eventId", type.equals("AdditionalInformationRequested") ? "request" : type)
				.put("eventType", type).put("schemaVersion", 1).put("reservationProcessId", "process")
				.put("holdId", "hold").put("occurredAt", TIME.toString());
		if (!type.equals("AdditionalInformationRequested")) {
			input.put("groupId", "test-project").put("requestEventId", "request");
		}
		switch (type) {
			case "AdditionalInformationRequested" -> input.put("requiredField", "PHONE_NUMBER")
					.put("message", "Presentation only").put("expiresAt", EXPIRY.toString());
			case "AppointmentConfirmed" -> input.put("reservationId", 8251).put("professionalId", 202)
					.put("date", "2030-01-03").put("startTime", "18:00").put("status", "CONFIRMED");
			case "AdditionalInformationRejected" -> input.put("reason", "INVALID_PHONE_NUMBER")
					.put("message", "Presentation only").put("expiresAt", EXPIRY.toString());
			case "AppointmentProcessExpired" -> input.put("reason", "HOLD_EXPIRED");
			case "AppointmentProcessInvalid" -> input.put("reason", "REQUEST_EVENT_MISMATCH");
			default -> throw new IllegalArgumentException("Unsupported test fixture");
		}
		return input;
	}

	@Test
	void publicationLeaseAndAttemptLimitSurviveRestartAndFenceStaleCompletion() {
		applyRequest(); var original = prepare("leased", "00000001234");
		var lease = java.time.Duration.ofSeconds(1);
		var first = phones.claimPublication("leased", TIME, lease, 3).orElseThrow();
		assertThat(phones.claimPublication("leased", TIME, lease, 3)).isEmpty();
		assertThat(phones.findReady(TIME, 10, 3)).isEmpty();
		context.close(); startContext();
		var second = phones.claimPublication("leased", TIME.plusSeconds(2), lease, 3).orElseThrow();
		assertThat(second.intent()).isEqualTo(original);
		assertThat(second.token()).isNotEqualTo(first.token());
		phones.completePublication("leased", first.token(), TIME.plusSeconds(2));
		assertThat(phones.findOwned(id, owner()).orElseThrow().state()).isEqualTo(PhoneSubmissionIntent.State.PENDING);
		phones.deferPublication("leased", second.token(), TIME.plusSeconds(4));
		assertThat(phones.findReady(TIME.plusSeconds(3), 10, 3)).isEmpty();
		assertThat(phones.findReady(TIME.plusSeconds(4), 10, 3)).containsExactly("leased");
		var third = phones.claimPublication("leased", TIME.plusSeconds(4), lease, 3).orElseThrow();
		phones.deferPublication("leased", third.token(), TIME.plusSeconds(5));
		context.close(); startContext();
		assertThat(phones.findReady(TIME.plusSeconds(6), 10, 3)).isEmpty();
		assertThat(phones.claimPublication("leased", TIME.plusSeconds(6), lease, 3)).isEmpty();
		assertThat(phones.findOwned(id, owner()).orElseThrow().eventId()).isEqualTo("leased");
	}

	@Test
	void concurrentPublicationClaimsHaveOnlyOneWinner() throws Exception {
		applyRequest(); prepare("concurrent-lease", "00000001234");
		try (var pool = Executors.newFixedThreadPool(2)) {
			var start = new CountDownLatch(1);
			Callable<Boolean> action = () -> {
				assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
				return phones.claimPublication("concurrent-lease", TIME, java.time.Duration.ofMinutes(3), 3).isPresent();
			};
			var first = pool.submit(action); var second = pool.submit(action); start.countDown();
			assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
					.containsExactlyInAnyOrder(true, false);
		}
	}

	@Test
	void fencedAcknowledgmentWaitsForFinalCatedraResultAndCannotRegressATerminalProcess() {
		applyRequest(); prepare("ack-lease", "00000001234");
		var claim = phones.claimPublication("ack-lease", TIME, java.time.Duration.ofMinutes(3), 3).orElseThrow();
		phones.completePublication("ack-lease", claim.token(), TIME.plusSeconds(1));
		assertThat(phones.findOwned(id, owner()).orElseThrow().state()).isEqualTo(PhoneSubmissionIntent.State.PUBLISHED);
		assertThat(process().getStatus()).isEqualTo(ReservationProcessStatus.WAITING_FOR_CONFIRMATION);
		assertThat(phones.findReady(TIME, 10, 3)).isEmpty();
		phones.completePublication("ack-lease", claim.token(), TIME.plusSeconds(2));
		assertThat(process().getStatus()).isEqualTo(ReservationProcessStatus.WAITING_FOR_CONFIRMATION);
	}

	@Test
	void expiredOrTerminalProcessesCannotBeClaimedForPublication() {
		applyRequest(); prepare("expired-lease", "00000001234");
		assertThat(phones.claimPublication("expired-lease", EXPIRY, java.time.Duration.ofMinutes(3), 3)).isEmpty();
	}

	@Test
	void recoveryAfterBrokerAcceptanceAndLocalCommitFailureRetainsIdentityAndHasNoOpenNetworkTransaction() throws Exception {
		applyRequest(); prepare("crash-window", "00000001234");
		execute("CREATE FUNCTION " + schema + ".reject_recovery_commit() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN "
				+ "IF NEW.publication_state='PUBLISHED' THEN RAISE EXCEPTION 'private diagnostic'; END IF; RETURN NEW; END $$");
		execute("CREATE CONSTRAINT TRIGGER reject_recovery_commit AFTER UPDATE ON " + schema
				+ ".reservation_phone_submission DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION " + schema + ".reject_recovery_commit()");
		var observed = new java.util.ArrayList<PhoneSubmissionIntent>();
		ar.edu.um.prog2.appointmentreservation.reservation.application.port.out.PhoneSubmissionPublisher publisher = intent -> {
			assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
			observed.add(intent);
			return java.util.concurrent.CompletableFuture.completedFuture(null);
		};
		var preparation = new ar.edu.um.prog2.appointmentreservation.reservation.application.usecase.PreparePhoneSubmissionUseCase(
				this::owner, phones, "test-project", java.time.Clock.fixed(TIME, java.time.ZoneOffset.UTC));
		var coordinator = new ar.edu.um.prog2.appointmentreservation.reservation.application.usecase.SubmitPhoneInformationUseCase(
				preparation, phones, publisher, java.time.Clock.fixed(TIME, java.time.ZoneOffset.UTC), 3,
				java.time.Duration.ofSeconds(70), java.time.Duration.ofSeconds(1), java.time.Duration.ofSeconds(5));
		assertThat(coordinator.recover(10).deferred()).isEqualTo(1);
		assertThat(phones.findOwned(id, owner()).orElseThrow().state()).isEqualTo(PhoneSubmissionIntent.State.PENDING);
		execute("DROP TRIGGER reject_recovery_commit ON " + schema + ".reservation_phone_submission");
		context.close(); startContext();
		var restartedPreparation = new ar.edu.um.prog2.appointmentreservation.reservation.application.usecase.PreparePhoneSubmissionUseCase(
				this::owner, phones, "test-project", java.time.Clock.fixed(TIME.plusSeconds(71), java.time.ZoneOffset.UTC));
		var restarted = new ar.edu.um.prog2.appointmentreservation.reservation.application.usecase.SubmitPhoneInformationUseCase(
				restartedPreparation, phones, publisher, java.time.Clock.fixed(TIME.plusSeconds(71), java.time.ZoneOffset.UTC), 3,
				java.time.Duration.ofSeconds(70), java.time.Duration.ofSeconds(1), java.time.Duration.ofSeconds(5));
		assertThat(restarted.recover(10).published()).isEqualTo(1);
		assertThat(observed).hasSize(2);
		assertThat(observed.get(0)).isEqualTo(observed.get(1));
		assertThat(process().getStatus()).isEqualTo(ReservationProcessStatus.WAITING_FOR_CONFIRMATION);
	}

	@Test
	void ownedProgressIsAReadOnlySnapshotAndRequiresAnAppliedNonConflictingPhoneRequest() {
		var claimed = claim();
		store.recordInitialConfirmation(id, claimed.version(), owner(),
				new ar.edu.um.prog2.appointmentreservation.reservation.application.model.InitialReservationConfirmation(
						"process", "Ana", "Perez", EXPIRY));
		var before = store.findOwnedProgress(id, owner()).orElseThrow();
		assertThat(before.status()).isEqualTo(ReservationProcessStatus.WAITING_FOR_PHONE);
		assertThat(before.phoneRequestReady()).isFalse();
		events.receive(request()); events.applyPending("request");
		long version = process().getVersion();
		var ready = store.findOwnedProgress(id, owner()).orElseThrow();
		assertThat(ready.phoneRequestReady()).isTrue();
		assertThat(ready.reason()).isNull();
		assertThat(process().getVersion()).isEqualTo(version);
		assertThat(phones.findOwned(id, owner())).isEmpty();
		assertThat(store.findOwnedProgress(id, new AuthenticatedReservationUser(ownerId + 1000, "other"))).isEmpty();
		assertThat(store.findOwnedProgress(Long.MAX_VALUE, owner())).isEmpty();
		var conflicting = new ReservationActionEvent("request", Signal.PHONE_REQUESTED, "process", "hold", null, TIME,
				EXPIRY.plusSeconds(1), null, null, null);
		events.receive(conflicting);
		assertThat(store.findOwnedProgress(id, owner()).orElseThrow().phoneRequestReady()).isFalse();
	}

	@Test
	void progressObservesRejectionAndConfirmationWithoutDisclosingPatientOrTechnicalIdentity() {
		applyRequest(); prepare("progress-phone", "00000001234");
		events.receive(rejection()); events.applyPending("rejected");
		var rejected = store.findOwnedProgress(id, owner()).orElseThrow();
		assertThat(rejected.reason()).isEqualTo("INVALID_PHONE_NUMBER");
		assertThat(rejected.phoneRequestReady()).isTrue();
		assertThat(rejected.status()).isEqualTo(ReservationProcessStatus.WAITING_FOR_PHONE);
		events.receive(confirmed("progress-final", 8251, "request")); events.applyPending("progress-final");
		assertThat(store.findOwnedProgress(id, owner()).orElseThrow().status()).isEqualTo(ReservationProcessStatus.CONFIRMED);
	}

	private PhoneSubmissionIntent prepare(String eventId, String phone) {
		return phones.prepare(owner(), new NewPhoneSubmission(id, eventId, phone, "test-project"), TIME);
	}
	private ReservationActionEvent rejection() {
		return new ReservationActionEvent("rejected", Signal.PHONE_REJECTED, "process", "hold", "request", TIME,
				EXPIRY, null, null, "INVALID_PHONE_NUMBER");
	}
	private int query(String sql) throws SQLException {
		try (var connection = connection(); var statement = connection.createStatement(); var result = statement.executeQuery(sql)) {
			result.next(); return result.getInt(1);
		}
	}

	private void applyRequest() { claim(); events.receive(request()); assertThat(events.applyPending("request").state()).isEqualTo(State.APPLIED); }
	private ar.edu.um.prog2.appointmentreservation.reservation.application.model.StoredReservationProcess claim() {
		return store.claimInitialConfirmation(id, 0, owner(), new ConfirmationPatient("Ana", "Perez"), TIME);
	}
	private ReservationActionEvent request() {
		return new ReservationActionEvent("request", Signal.PHONE_REQUESTED, "process", "hold", null, TIME, EXPIRY, null, null, null);
	}
	private ReservationActionEvent confirmed(String eventId, long reservationId, String request) {
		return new ReservationActionEvent(eventId, Signal.RESERVATION_CONFIRMED, "process", "hold", request, TIME, null,
				reservationId, SELECTION, null);
	}
	private AuthenticatedReservationUser owner() { return new AuthenticatedReservationUser(ownerId, "alice"); }
	private ReservationProcessEntity process() { return processes.findById(id).orElseThrow(); }
	private Connection connection() throws SQLException { return DriverManager.getConnection(url, username, password); }
	private void execute(String sql) throws SQLException { try (var connection = connection(); var statement = connection.createStatement()) { statement.execute(sql); } }
	private static String required(String key) {
		String value = System.getenv(key);
		if (value == null || value.isBlank()) { throw new IllegalStateException("Set " + key + " for postgres-integration"); }
		return value;
	}
	private static <T> List<T> concurrently(Callable<T> operation) throws Exception {
		var start = new CountDownLatch(1);
		Callable<T> task = () -> { assertThat(start.await(5, TimeUnit.SECONDS)).isTrue(); return operation.call(); };
		try (var pool = Executors.newFixedThreadPool(2)) {
			var first = pool.submit(task); var second = pool.submit(task); start.countDown();
			return List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS));
		}
	}
}
