package ar.edu.um.prog2.appointmentreservation.user.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.security.crypto.password.PasswordEncoder;

import ar.edu.um.prog2.appointmentreservation.AppointmentReservationApplication;
import ar.edu.um.prog2.appointmentreservation.user.application.exception.DuplicateUserException;
import ar.edu.um.prog2.appointmentreservation.user.application.exception.DuplicateUserException.Field;
import ar.edu.um.prog2.appointmentreservation.user.application.model.NewRegisteredUser;
import ar.edu.um.prog2.appointmentreservation.user.application.model.RegisteredUser;
import ar.edu.um.prog2.appointmentreservation.user.application.model.RegisterUserCommand;
import ar.edu.um.prog2.appointmentreservation.user.application.port.out.PasswordHasher;
import ar.edu.um.prog2.appointmentreservation.user.application.port.out.UserRegistrationStore;
import ar.edu.um.prog2.appointmentreservation.user.application.usecase.RegisterUserUseCase;
import ar.edu.um.prog2.appointmentreservation.user.infrastructure.persistence.entity.AuthorityEntity;
import ar.edu.um.prog2.appointmentreservation.user.infrastructure.persistence.repository.UserJpaRepository;

class UserRegistrationIT {

	private static final String TEST_PASSWORD = "  test-only registration passphrase  ";
	private String url;
	private String username;
	private String password;
	private String schema;
	private ConfigurableApplicationContext context;
	private RegisterUserUseCase useCase;
	private UserRegistrationStore store;
	private UserJpaRepository users;
	private PasswordEncoder encoder;

	@BeforeEach
	void startWithAnIsolatedMigratedSchema() throws SQLException {
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
				"spring.datasource.url", url,
				"spring.datasource.username", username,
				"spring.datasource.password", password,
				"spring.flyway.schemas", schema,
				"spring.flyway.default-schema", schema,
				"spring.flyway.create-schemas", false,
				"spring.jpa.properties.hibernate.default_schema", schema,
				"spring.jpa.hibernate.ddl-auto", "validate");
		context = new SpringApplicationBuilder(AppointmentReservationApplication.class)
				.web(WebApplicationType.NONE)
				.initializers(application -> application.getEnvironment().getPropertySources().addFirst(
						new MapPropertySource("userRegistrationTest", properties)))
				.run();
		useCase = context.getBean(RegisterUserUseCase.class);
		store = context.getBean(UserRegistrationStore.class);
		users = context.getBean(UserJpaRepository.class);
		encoder = context.getBean(PasswordEncoder.class);
	}

	@AfterEach
	void closeAndRemoveOnlyThisTestSchema() throws SQLException {
		try {
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
	void registersAnActiveUserWithArgon2idAndOnlyTheBaseAuthority() {
		RegisteredUser result = useCase.register(command("Alice", "ALICE@example.test"));
		var loaded = users.findByLoginIgnoreCase("ALICE").orElseThrow();
		assertThat(result.id()).isPositive().isEqualTo(loaded.getId());
		assertThat(result.login()).isEqualTo("alice");
		assertThat(result.firstName()).isEqualTo("Ana");
		assertThat(result.lastName()).isEqualTo("Perez");
		assertThat(result.email()).isEqualTo("alice@example.test");
		assertThat(result.imageUrl()).isNull();
		assertThat(result.langKey()).isEqualTo("es");
		assertThat(result.activated()).isTrue();
		assertThat(result.authorities()).containsExactly("ROLE_USER");
		assertThat(loaded.isActivated()).isTrue();
		assertThat(loaded.getPasswordHash()).startsWith("$argon2id$v=19$m=19456,t=2,p=1$");
		assertThat(encoder.matches(TEST_PASSWORD, loaded.getPasswordHash())).isTrue();
		assertThat(loaded.getCreatedBy()).isEqualTo("alice");
		assertThat(loaded.getCreatedDate()).isNotNull();
		assertThat(loaded.getLastModifiedBy()).isNull();
		assertThat(loaded.getAuthorities()).extracting(AuthorityEntity::getName).containsExactly("ROLE_USER");
		assertThat(count("app_user_authority")).isEqualTo(1);
	}

	@ParameterizedTest
	@EnumSource(Field.class)
	void rejectsCaseInsensitiveDuplicatesThroughPrechecks(Field field) {
		useCase.register(command("Alice", "alice@example.test"));
		RegisterUserCommand duplicate = command(field == Field.LOGIN ? "ALICE" : "bob",
				field == Field.EMAIL ? "ALICE@EXAMPLE.TEST" : "other@example.test");
		assertThatThrownBy(() -> useCase.register(duplicate))
				.isInstanceOfSatisfying(DuplicateUserException.class,
						failure -> assertThat(failure.field()).isEqualTo(field));
		assertThat(users.count()).isEqualTo(1);
	}

	@ParameterizedTest
	@EnumSource(Field.class)
	void translatesDatabaseConflictsEvenWhenPrechecksWereBypassed(Field field) {
		useCase.register(command("alice", "alice@example.test"));
		NewRegisteredUser duplicate = new NewRegisteredUser(field == Field.LOGIN ? "ALICE" : "bob",
				encoder.encode(TEST_PASSWORD), null, null,
				field == Field.EMAIL ? "ALICE@EXAMPLE.TEST" : "other@example.test", null, "es",
				true, Set.of("ROLE_USER"), "backend-test");
		assertThatThrownBy(() -> store.create(duplicate))
				.isInstanceOfSatisfying(DuplicateUserException.class, failure -> {
					assertThat(failure.field()).isEqualTo(field);
					assertThat(failure.getCause()).isNull();
				});
		assertThat(users.count()).isEqualTo(1);
		assertThat(count("app_user_authority")).isEqualTo(1);
	}

	@Test
	void failsWithoutCreatingAUserWhenTheBaseAuthorityIsMissing() throws SQLException {
		execute("DELETE FROM " + schema + ".authority WHERE name='ROLE_USER'");
		assertThatThrownBy(() -> useCase.register(command("alice", "alice@example.test")))
				.isInstanceOf(IllegalStateException.class)
				.hasMessage("Required registration authority is unavailable");
		assertThat(users.count()).isZero();
		assertThat(count("app_user_authority")).isZero();
	}

	@Test
	void rollsBackTheUserIfSavingItsAuthorityAssociationFails() throws SQLException {
		// Test-only trigger, in this test's own schema, forces failure after the user INSERT.
		execute("CREATE FUNCTION " + schema + ".reject_test_membership() RETURNS trigger LANGUAGE plpgsql "
				+ "AS $$ BEGIN RAISE EXCEPTION 'Forced registration test failure'; END $$");
		execute("CREATE TRIGGER reject_test_membership BEFORE INSERT ON " + schema + ".app_user_authority "
				+ "FOR EACH ROW EXECUTE FUNCTION " + schema + ".reject_test_membership()");
		assertThatThrownBy(() -> useCase.register(command("alice", "alice@example.test")))
				.isInstanceOf(RuntimeException.class).isNotInstanceOf(DuplicateUserException.class)
				.satisfies(failure -> assertThat(sqlState(failure)).isEqualTo("P0001"));
		assertThat(users.count()).isZero();
		assertThat(count("app_user_authority")).isZero();
		assertThat(count("authority")).isEqualTo(1);
	}

	@Test
	void concurrentRegistrationsYieldOneUserAndOneIdentifiedConflict() throws Exception {
		CyclicBarrier readyToCreate = new CyclicBarrier(2);
		UserRegistrationStore competingStore = new UserRegistrationStore() {
			@Override
			public boolean loginExists(String login) { return store.loginExists(login); }
			@Override
			public boolean emailExists(String email) { return store.emailExists(email); }
			@Override
			public RegisteredUser create(NewRegisteredUser user) {
				try {
					readyToCreate.await(10, TimeUnit.SECONDS);
				}
				catch (InterruptedException failure) {
					Thread.currentThread().interrupt();
					throw new IllegalStateException("Concurrent test interrupted", failure);
				}
				catch (BrokenBarrierException | TimeoutException failure) {
					throw new IllegalStateException("Concurrent test barrier failed", failure);
				}
				return store.create(user);
			}
		};
		RegisterUserUseCase competingUseCase = new RegisterUserUseCase(competingStore,
				context.getBean(PasswordHasher.class));
		try (var executor = Executors.newFixedThreadPool(2)) {
			var first = executor.submit(() -> attempt(competingUseCase, "first@example.test"));
			var second = executor.submit(() -> attempt(competingUseCase, "second@example.test"));
			assertThat(java.util.Arrays.asList(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS)))
					.containsExactlyInAnyOrder("created", "LOGIN");
		}
		assertThat(users.count()).isEqualTo(1);
		assertThat(count("app_user_authority")).isEqualTo(1);
	}

	private static String attempt(RegisterUserUseCase useCase, String email) {
		try {
			useCase.register(command("competing-user", email));
			return "created";
		}
		catch (DuplicateUserException conflict) {
			return conflict.field().name();
		}
	}

	private static String sqlState(Throwable failure) {
		for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
			if (cause instanceof SQLException sql) {
				return sql.getSQLState();
			}
		}
		return null;
	}

	private long count(String table) {
		try (Connection connection = connection(); var statement = connection.createStatement();
				var result = statement.executeQuery("SELECT count(*) FROM " + schema + "." + table)) {
			result.next();
			return result.getLong(1);
		}
		catch (SQLException failure) {
			throw new IllegalStateException("Test query failed", failure);
		}
	}

	private void execute(String sql) throws SQLException {
		try (Connection connection = connection(); var statement = connection.createStatement()) {
			statement.execute(sql);
		}
	}

	private Connection connection() throws SQLException {
		return DriverManager.getConnection(url, username, password);
	}

	private static RegisterUserCommand command(String login, String email) {
		return new RegisterUserCommand(login, TEST_PASSWORD, "Ana", "Perez", email, null, "es");
	}

	private static String requiredEnvironment(String name) {
		String value = System.getenv(name);
		if (value == null || value.isBlank()) {
			throw new IllegalStateException("Set " + name + " for postgres-integration");
		}
		return value;
	}
}
