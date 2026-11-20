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
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;

import ar.edu.um.prog2.appointmentreservation.AppointmentReservationApplication;
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
import ar.edu.um.prog2.appointmentreservation.reservation.application.port.out.ReservationProcessStore;
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

@ExtendWith(OutputCaptureExtension.class)
class ReservationEventPersistenceIT {
	private static final Instant TIME = Instant.parse("2026-11-20T12:00:00.123456789Z");
	private static final Instant EXPIRY = TIME.plusSeconds(300);
	private static final StartReservationCommand SELECTION = new StartReservationCommand(202, LocalDate.of(2030, 1, 3), LocalTime.of(18, 0));
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
		assertThat(output.getAll()).doesNotContain(FinalUserJwtTestSupport.signingSecret(),
				"test-only-private-commit-diagnostic", "Failing row contains", "not-a-real-catedra-token");
	}

	@Test
	void v5PreservesOwnershipSelectionExpiryAndAuditOfTheExistingV4Row() {
		assertThat(context.getBean(Flyway.class).info().applied()).extracting(m -> m.getVersion().toString())
				.containsExactly("1", "2", "3", "4", "5");
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
		// Simulates only the durable phase that A8.3 will own; no message is published here.
		execute("UPDATE " + schema + ".reservation_process SET process_status='PHONE_SUBMISSION_PENDING',version=version+1 WHERE id=" + id);
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
