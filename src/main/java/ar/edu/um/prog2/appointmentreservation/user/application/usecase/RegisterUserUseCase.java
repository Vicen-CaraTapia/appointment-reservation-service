package ar.edu.um.prog2.appointmentreservation.user.application.usecase;

import java.util.Locale;
import java.util.Objects;
import java.util.Set;

import ar.edu.um.prog2.appointmentreservation.user.application.exception.DuplicateUserException;
import ar.edu.um.prog2.appointmentreservation.user.application.exception.DuplicateUserException.Field;
import ar.edu.um.prog2.appointmentreservation.user.application.model.NewRegisteredUser;
import ar.edu.um.prog2.appointmentreservation.user.application.model.RegisteredUser;
import ar.edu.um.prog2.appointmentreservation.user.application.model.RegisterUserCommand;
import ar.edu.um.prog2.appointmentreservation.user.application.port.out.PasswordHasher;
import ar.edu.um.prog2.appointmentreservation.user.application.port.out.UserRegistrationStore;

/** Coordinates registration after input validation, independently of HTTP/JPA. */
public final class RegisterUserUseCase {

	private final UserRegistrationStore users;
	private final PasswordHasher passwords;

	public RegisterUserUseCase(UserRegistrationStore users, PasswordHasher passwords) {
		this.users = Objects.requireNonNull(users);
		this.passwords = Objects.requireNonNull(passwords);
	}

	public RegisteredUser register(RegisterUserCommand command) {
		String login = command.login().toLowerCase(Locale.ROOT);
		String email = command.email().toLowerCase(Locale.ROOT);
		if (users.loginExists(login)) {
			throw new DuplicateUserException(Field.LOGIN);
		}
		if (users.emailExists(email)) {
			throw new DuplicateUserException(Field.EMAIL);
		}
		String hash = passwords.hash(command.password());
		return users.create(new NewRegisteredUser(login, hash, command.firstName(), command.lastName(),
				email, command.imageUrl(), command.langKey(), true, Set.of("ROLE_USER"), login));
	}
}
