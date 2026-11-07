package ar.edu.um.prog2.appointmentreservation.user.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import ar.edu.um.prog2.appointmentreservation.AppointmentReservationApplication;
import ar.edu.um.prog2.appointmentreservation.support.CatedraIntegrationTestSupport;
import ar.edu.um.prog2.appointmentreservation.support.FinalUserJwtTestSupport;
import ar.edu.um.prog2.appointmentreservation.user.infrastructure.persistence.entity.AuthorityEntity;
import ar.edu.um.prog2.appointmentreservation.user.infrastructure.persistence.entity.UserEntity;
import ar.edu.um.prog2.appointmentreservation.user.infrastructure.persistence.repository.UserJpaRepository;

class UserPersistenceIT {

	private String url;
	private String username;
	private String password;
	private String schema;
	private ConfigurableApplicationContext context;
	private UserJpaRepository users;
	private PasswordEncoder encoder;
	private TransactionTemplate transactions;
	private EntityManager entityManager;

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
				.initializers(CatedraIntegrationTestSupport::configure)
				.web(WebApplicationType.NONE)
				.initializers(application -> application.getEnvironment().getPropertySources().addFirst(
						new MapPropertySource("userPersistenceTest", properties)))
				.run();
		users = context.getBean(UserJpaRepository.class);
		encoder = context.getBean(PasswordEncoder.class);
		transactions = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
		entityManager = SharedEntityManagerCreator.createSharedEntityManager(
				context.getBean(EntityManagerFactory.class));
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
	void persistsAndReloadsTheUserAndItsAssignedAuthority() {
		String encoded = encoder.encode("test-only-persistence-passphrase");
		UserEntity saved = transactions.execute(status -> {
			UserEntity user = user("Alice", "ALICE@example.test", encoded);
			user.grantAuthority(entityManager.getReference(AuthorityEntity.class, "ROLE_USER"), "backend-test");
			return users.saveAndFlush(user);
		});

		UserEntity loaded = users.findByLoginIgnoreCase("ALICE").orElseThrow();
		assertThat(loaded.getId()).isEqualTo(saved.getId()).isPositive();
		assertThat(loaded.getLogin()).isEqualTo("alice");
		assertThat(loaded.getFirstName()).isEqualTo("Alice");
		assertThat(loaded.getLastName()).isEqualTo("Example");
		assertThat(loaded.getEmail()).isEqualTo("alice@example.test");
		assertThat(loaded.getImageUrl()).isNull();
		assertThat(loaded.getLangKey()).isEqualTo("es");
		assertThat(loaded.isActivated()).isFalse();
		assertThat(loaded.getCreatedBy()).isEqualTo("backend-test");
		assertThat(loaded.getCreatedDate()).isNotNull();
		assertThat(loaded.getLastModifiedBy()).isNull();
		assertThat(loaded.getLastModifiedDate()).isNull();
		assertThat(loaded.getAuthorities()).extracting(AuthorityEntity::getName).containsExactly("ROLE_USER");
		assertThat(loaded.getPasswordHash()).isEqualTo(encoded);
		assertThat(encoder.matches("test-only-persistence-passphrase", loaded.getPasswordHash())).isTrue();
		assertThat(encoder.matches("wrong-test-passphrase", loaded.getPasswordHash())).isFalse();
	}

	@Test
	void rejectsDuplicateLoginThroughTheRepository() {
		String encoded = encoder.encode("test-only-persistence-passphrase");
		users.saveAndFlush(user("alice", "alice@example.test", encoded));

		assertThatThrownBy(() -> users.saveAndFlush(user("ALICE", "other@example.test", encoded)))
				.isInstanceOf(DataIntegrityViolationException.class);
		assertThat(users.count()).isEqualTo(1);
	}

	@Test
	void rejectsDuplicateEmailThroughTheRepository() {
		String encoded = encoder.encode("test-only-persistence-passphrase");
		users.saveAndFlush(user("alice", "alice@example.test", encoded));

		assertThatThrownBy(() -> users.saveAndFlush(user("bob", "ALICE@example.test", encoded)))
				.isInstanceOf(DataIntegrityViolationException.class);
		assertThat(users.count()).isEqualTo(1);
	}

	@Test
	void recordsAuditWhenOnlyAuthorityMembershipChanges() {
		String encoded = encoder.encode("test-only-persistence-passphrase");
		users.saveAndFlush(user("alice", "alice@example.test", encoded));

		transactions.executeWithoutResult(status -> {
			UserEntity user = users.findByLoginIgnoreCase("alice").orElseThrow();
			user.grantAuthority(entityManager.getReference(AuthorityEntity.class, "ROLE_USER"), "role-manager");
			users.flush();
		});

		UserEntity loaded = users.findByLoginIgnoreCase("alice").orElseThrow();
		assertThat(loaded.getAuthorities()).extracting(AuthorityEntity::getName).containsExactly("ROLE_USER");
		assertThat(loaded.getCreatedBy()).isEqualTo("backend-test");
		assertThat(loaded.getLastModifiedBy()).isEqualTo("role-manager");
		assertThat(loaded.getLastModifiedDate()).isNotNull();
	}

	@Test
	void recordsBackendModificationWithoutChangingCreationAudit() {
		String encoded = encoder.encode("test-only-persistence-passphrase");
		users.saveAndFlush(user("alice", "alice@example.test", encoded));
		Instant created = users.findByLoginIgnoreCase("alice").orElseThrow().getCreatedDate();

		transactions.executeWithoutResult(status -> {
			UserEntity user = users.findByLoginIgnoreCase("alice").orElseThrow();
			user.updateProfile("Updated", "Name", "UPDATED@example.test", "https://example.test/avatar",
					"en", "backend-editor");
			user.activate("backend-editor");
			users.flush();
		});

		UserEntity loaded = users.findByLoginIgnoreCase("alice").orElseThrow();
		assertThat(loaded.getFirstName()).isEqualTo("Updated");
		assertThat(loaded.getEmail()).isEqualTo("updated@example.test");
		assertThat(loaded.getImageUrl()).isEqualTo("https://example.test/avatar");
		assertThat(loaded.isActivated()).isTrue();
		assertThat(loaded.getCreatedBy()).isEqualTo("backend-test");
		assertThat(loaded.getCreatedDate()).isEqualTo(created);
		assertThat(loaded.getLastModifiedBy()).isEqualTo("backend-editor");
		assertThat(loaded.getLastModifiedDate()).isAfterOrEqualTo(created);
	}

	private static UserEntity user(String login, String email, String encodedPassword) {
		return new UserEntity(login, encodedPassword, "Alice", "Example", email, null, "es", "backend-test");
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
