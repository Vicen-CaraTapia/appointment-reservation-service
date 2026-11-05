package ar.edu.um.prog2.appointmentreservation.user.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;

import ar.edu.um.prog2.appointmentreservation.user.application.exception.DuplicateUserException;
import ar.edu.um.prog2.appointmentreservation.user.application.exception.DuplicateUserException.Field;
import ar.edu.um.prog2.appointmentreservation.user.application.model.NewRegisteredUser;
import ar.edu.um.prog2.appointmentreservation.user.application.model.RegisteredUser;
import ar.edu.um.prog2.appointmentreservation.user.application.model.RegisterUserCommand;
import ar.edu.um.prog2.appointmentreservation.user.application.port.out.PasswordHasher;
import ar.edu.um.prog2.appointmentreservation.user.application.port.out.UserRegistrationStore;

class RegisterUserUseCaseTest {

	private static final String PASSWORD = "  test-only passphrase  ";
	private static final String HASH = "$argon2id$v=19$m=19456,t=2,p=1$testSalt$testHash";

	private final UserRegistrationStore users = mock(UserRegistrationStore.class);
	private final PasswordHasher passwords = mock(PasswordHasher.class);
	private final RegisterUserUseCase useCase = new RegisterUserUseCase(users, passwords);

	@Test
	void normalizesIdentityAndStoresOnlyTheHashWithBackendAssignedState() {
		when(passwords.hash(PASSWORD)).thenReturn(HASH);
		RegisteredUser saved = savedUser();
		when(users.create(any())).thenReturn(saved);

		assertThat(useCase.register(command())).isSameAs(saved);

		verify(users).loginExists("user");
		verify(users).emailExists("user@example.com");
		verify(passwords).hash(PASSWORD);
		ArgumentCaptor<NewRegisteredUser> captured = ArgumentCaptor.forClass(NewRegisteredUser.class);
		verify(users).create(captured.capture());
		NewRegisteredUser user = captured.getValue();
		assertThat(user.login()).isEqualTo("user");
		assertThat(user.email()).isEqualTo("user@example.com");
		assertThat(user.passwordHash()).isEqualTo(HASH).isNotEqualTo(PASSWORD);
		assertThat(user.firstName()).isEqualTo("Ana");
		assertThat(user.lastName()).isEqualTo("Perez");
		assertThat(user.imageUrl()).isEqualTo("https://example.com/avatar.png");
		assertThat(user.langKey()).isEqualTo("es");
		assertThat(user.activated()).isTrue();
		assertThat(user.authorities()).containsExactly("ROLE_USER");
		assertThat(user.createdBy()).isEqualTo("user");
	}

	@Test
	void preservesNullableProfileFields() {
		when(passwords.hash(PASSWORD)).thenReturn(HASH);
		useCase.register(new RegisterUserCommand("User", PASSWORD, null, null,
				"USER@Example.COM", null, "es"));
		ArgumentCaptor<NewRegisteredUser> captured = ArgumentCaptor.forClass(NewRegisteredUser.class);
		verify(users).create(captured.capture());
		assertThat(captured.getValue().firstName()).isNull();
		assertThat(captured.getValue().lastName()).isNull();
		assertThat(captured.getValue().imageUrl()).isNull();
	}

	@Test
	void rejectsDuplicateLoginBeforeHashingOrSaving() {
		when(users.loginExists("user")).thenReturn(true);
		assertDuplicate(Field.LOGIN);
		verify(users, never()).emailExists(any());
		verify(users, never()).create(any());
		verifyNoInteractions(passwords);
	}

	@Test
	void rejectsDuplicateEmailBeforeHashingOrSaving() {
		when(users.emailExists("user@example.com")).thenReturn(true);
		assertDuplicate(Field.EMAIL);
		verify(users, never()).create(any());
		verifyNoInteractions(passwords);
	}

	@ParameterizedTest
	@EnumSource(Field.class)
	void propagatesConflictsDetectedDuringCreation(Field field) {
		when(passwords.hash(PASSWORD)).thenReturn(HASH);
		DuplicateUserException conflict = new DuplicateUserException(field);
		when(users.create(any())).thenThrow(conflict);
		assertThatThrownBy(() -> useCase.register(command())).isSameAs(conflict);
	}

	@Test
	void doesNotSaveIfHashingFails() {
		RuntimeException failure = new IllegalStateException("Hashing unavailable");
		when(passwords.hash(PASSWORD)).thenThrow(failure);
		assertThatThrownBy(() -> useCase.register(command())).isSameAs(failure);
		verify(users, never()).create(any());
	}

	@Test
	void doesNotMisclassifyInfrastructureFailuresAsDuplicates() {
		RuntimeException failure = new IllegalStateException("Storage unavailable");
		when(users.loginExists("user")).thenThrow(failure);
		assertThatThrownBy(() -> useCase.register(command())).isSameAs(failure);
		verifyNoInteractions(passwords);
		verify(users, never()).create(any());
	}

	@Test
	void redactsCredentialBearingModels() {
		assertThat(command().toString()).isEqualTo("RegisterUserCommand[redacted]");
		NewRegisteredUser user = new NewRegisteredUser("user", HASH, null, null, "user@example.com",
				null, "es", true, Set.of("ROLE_USER"), "user");
		assertThat(user.toString()).isEqualTo("NewRegisteredUser[redacted]");
	}

	@Test
	void authorityCollectionsCannotBeChangedAfterConstruction() {
		Set<String> authorities = new HashSet<>(Set.of("ROLE_USER"));
		NewRegisteredUser user = new NewRegisteredUser("user", HASH, null, null, "user@example.com",
				null, "es", true, authorities, "user");
		RegisteredUser result = new RegisteredUser(1L, "user", null, null, "user@example.com",
				null, "es", true, authorities);
		authorities.add("ROLE_ADMIN");
		assertThat(user.authorities()).containsExactly("ROLE_USER");
		assertThat(result.authorities()).containsExactly("ROLE_USER");
		assertThatThrownBy(() -> user.authorities().add("ROLE_ADMIN"))
				.isInstanceOf(UnsupportedOperationException.class);
		assertThatThrownBy(() -> result.authorities().add("ROLE_ADMIN"))
				.isInstanceOf(UnsupportedOperationException.class);
	}

	private void assertDuplicate(Field expected) {
		assertThatThrownBy(() -> useCase.register(command()))
				.isInstanceOfSatisfying(DuplicateUserException.class,
						failure -> assertThat(failure.field()).isEqualTo(expected))
				.hasMessage(expected == Field.LOGIN ? "Login already exists" : "Email already exists");
	}

	private static RegisterUserCommand command() {
		return new RegisterUserCommand("User", PASSWORD, "Ana", "Perez", "USER@Example.COM",
				"https://example.com/avatar.png", "es");
	}

	private static RegisteredUser savedUser() {
		return new RegisteredUser(1L, "user", "Ana", "Perez", "user@example.com",
				"https://example.com/avatar.png", "es", true, Set.of("ROLE_USER"));
	}
}
