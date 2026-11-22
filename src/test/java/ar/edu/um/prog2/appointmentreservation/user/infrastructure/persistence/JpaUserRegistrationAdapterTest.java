package ar.edu.um.prog2.appointmentreservation.user.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.SQLException;
import java.util.Set;

import jakarta.persistence.EntityManager;

import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import ar.edu.um.prog2.appointmentreservation.user.application.exception.DuplicateUserException;
import ar.edu.um.prog2.appointmentreservation.user.application.exception.DuplicateUserException.Field;
import ar.edu.um.prog2.appointmentreservation.user.application.model.NewRegisteredUser;
import ar.edu.um.prog2.appointmentreservation.user.application.model.RegisteredUser;
import ar.edu.um.prog2.appointmentreservation.user.infrastructure.persistence.entity.AuthorityEntity;
import ar.edu.um.prog2.appointmentreservation.user.infrastructure.persistence.entity.UserEntity;
import ar.edu.um.prog2.appointmentreservation.user.infrastructure.persistence.repository.UserJpaRepository;

class JpaUserRegistrationAdapterTest {

	private final UserJpaRepository users = mock(UserJpaRepository.class);
	private final EntityManager entityManager = mock(EntityManager.class);
	private final PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
	private final SimpleTransactionStatus transaction = new SimpleTransactionStatus();
	private final JpaUserRegistrationAdapter adapter = new JpaUserRegistrationAdapter(users, entityManager, manager);

	@Test
	void delegatesCaseInsensitivePrechecks() {
		when(users.existsByLoginIgnoreCase("user")).thenReturn(true);
		when(users.existsByEmailIgnoreCase("user@example.test")).thenReturn(true);
		assertThat(adapter.loginExists("user")).isTrue();
		assertThat(adapter.emailExists("user@example.test")).isTrue();
	}

	@Test
	void mapsPublicDataAndCommitsUserWithItsAuthority() {
		preparePersistence();
		RegisteredUser result = adapter.create(input(true));
		assertThat(result.login()).isEqualTo("user");
		assertThat(result.firstName()).isEqualTo("Ana");
		assertThat(result.lastName()).isEqualTo("Perez");
		assertThat(result.email()).isEqualTo("user@example.test");
		assertThat(result.imageUrl()).isNull();
		assertThat(result.langKey()).isEqualTo("es");
		assertThat(result.activated()).isTrue();
		assertThat(result.authorities()).containsExactly("ROLE_USER");
		verify(manager).commit(transaction);
		verify(manager, never()).rollback(any());
	}

	@Test
	void preservesTheActivationStateAssignedByTheApplication() {
		preparePersistence();
		assertThat(adapter.create(input(false)).activated()).isFalse();
	}

	@ParameterizedTest
	@EnumSource(Field.class)
	void translatesOnlyKnownUniqueViolationsWithoutLeakingDatabaseDiagnostics(Field field) {
		preparePersistence();
		String index = field == Field.LOGIN ? "ux_app_user_login_lower" : "ux_app_user_email_lower";
		when(users.saveAndFlush(any())).thenThrow(constraintFailure("23505", index));
		assertThatThrownBy(() -> adapter.create(input(true)))
				.isInstanceOfSatisfying(DuplicateUserException.class, failure -> {
					assertThat(failure.field()).isEqualTo(field);
					assertThat(failure.getCause()).isNull();
					assertThat(failure.getMessage()).doesNotContain("private database diagnostic");
				});
		verify(manager).rollback(transaction);
	}

	@ParameterizedTest
	@ValueSource(strings = {"23503", "23502"})
	void doesNotTreatOtherSqlStatesAsDuplicates(String state) {
		preparePersistence();
		RuntimeException failure = constraintFailure(state, "ux_app_user_login_lower");
		when(users.saveAndFlush(any())).thenThrow(failure);
		assertThatThrownBy(() -> adapter.create(input(true))).isSameAs(failure);
	}

	@Test
	void preservesUnknownUniqueConstraintFailures() {
		preparePersistence();
		RuntimeException failure = constraintFailure("23505", "some_other_unique_constraint");
		when(users.saveAndFlush(any())).thenThrow(failure);
		assertThatThrownBy(() -> adapter.create(input(true))).isSameAs(failure);
	}

	@Test
	void failsClosedWhenRequiredAuthorityIsMissing() {
		when(manager.getTransaction(any())).thenReturn(transaction);
		assertThatThrownBy(() -> adapter.create(input(true)))
				.isInstanceOf(IllegalStateException.class)
				.hasMessage("Required registration authority is unavailable");
		verify(users, never()).saveAndFlush(any());
		verify(manager).rollback(transaction);
	}

	@Test
	void preservesUnrelatedStorageFailures() {
		preparePersistence();
		RuntimeException failure = new IllegalStateException("Storage unavailable");
		when(users.saveAndFlush(any())).thenThrow(failure);
		assertThatThrownBy(() -> adapter.create(input(true))).isSameAs(failure);
		verify(manager).rollback(transaction);
	}

	private void preparePersistence() {
		when(manager.getTransaction(any())).thenReturn(transaction);
		when(entityManager.find(AuthorityEntity.class, "ROLE_USER")).thenReturn(new AuthorityEntity("ROLE_USER"));
		when(users.saveAndFlush(any(UserEntity.class))).thenAnswer(call -> call.getArgument(0));
	}

	private static NewRegisteredUser input(boolean activated) {
		return new NewRegisteredUser("user", "$argon2id$v=19$m=19456,t=2,p=1$testSalt$testHash",
				"Ana", "Perez", "user@example.test", null, "es", activated, Set.of("ROLE_USER"), "user");
	}

	private static RuntimeException constraintFailure(String state, String constraint) {
		return new DataIntegrityViolationException("private database diagnostic",
				new ConstraintViolationException("private database diagnostic",
						new SQLException("private database diagnostic", state), constraint));
	}
}
