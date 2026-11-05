package ar.edu.um.prog2.appointmentreservation.user.infrastructure.persistence;

import java.util.stream.Collectors;

import jakarta.persistence.EntityManager;

import org.hibernate.exception.ConstraintViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import ar.edu.um.prog2.appointmentreservation.user.application.exception.DuplicateUserException;
import ar.edu.um.prog2.appointmentreservation.user.application.exception.DuplicateUserException.Field;
import ar.edu.um.prog2.appointmentreservation.user.application.model.NewRegisteredUser;
import ar.edu.um.prog2.appointmentreservation.user.application.model.RegisteredUser;
import ar.edu.um.prog2.appointmentreservation.user.application.port.out.UserRegistrationStore;
import ar.edu.um.prog2.appointmentreservation.user.infrastructure.persistence.entity.AuthorityEntity;
import ar.edu.um.prog2.appointmentreservation.user.infrastructure.persistence.entity.UserEntity;
import ar.edu.um.prog2.appointmentreservation.user.infrastructure.persistence.repository.UserJpaRepository;

public final class JpaUserRegistrationAdapter implements UserRegistrationStore {

	private final UserJpaRepository users;
	private final EntityManager entityManager;
	private final TransactionTemplate transactions;

	public JpaUserRegistrationAdapter(UserJpaRepository users, EntityManager entityManager,
			PlatformTransactionManager transactionManager) {
		this.users = users;
		this.entityManager = entityManager;
		this.transactions = new TransactionTemplate(transactionManager);
	}

	@Override
	public boolean loginExists(String normalizedLogin) {
		return users.existsByLoginIgnoreCase(normalizedLogin);
	}

	@Override
	public boolean emailExists(String normalizedEmail) {
		return users.existsByEmailIgnoreCase(normalizedEmail);
	}

	@Override
	public RegisteredUser create(NewRegisteredUser input) {
		try {
			// Catch outside the transaction so both flush and commit failures are considered.
			return transactions.execute(status -> persist(input));
		}
		catch (RuntimeException failure) {
			for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
				if (cause instanceof ConstraintViolationException constraint
						&& "23505".equals(constraint.getSQLState())) {
					if ("ux_app_user_login_lower".equals(constraint.getConstraintName())) {
						throw new DuplicateUserException(Field.LOGIN);
					}
					if ("ux_app_user_email_lower".equals(constraint.getConstraintName())) {
						throw new DuplicateUserException(Field.EMAIL);
					}
				}
			}
			throw failure;
		}
	}

	private RegisteredUser persist(NewRegisteredUser input) {
		UserEntity user = new UserEntity(input.login(), input.passwordHash(), input.firstName(),
				input.lastName(), input.email(), input.imageUrl(), input.langKey(), input.createdBy());
		if (input.activated()) {
			user.activate(input.createdBy());
		}
		for (String name : input.authorities()) {
			AuthorityEntity authority = entityManager.find(AuthorityEntity.class, name);
			if (authority == null) {
				throw new IllegalStateException("Required registration authority is unavailable");
			}
			user.grantAuthority(authority, input.createdBy());
		}
		UserEntity saved = users.saveAndFlush(user);
		return new RegisteredUser(saved.getId(), saved.getLogin(), saved.getFirstName(), saved.getLastName(),
				saved.getEmail(), saved.getImageUrl(), saved.getLangKey(), saved.isActivated(),
				saved.getAuthorities().stream().map(AuthorityEntity::getName).collect(Collectors.toUnmodifiableSet()));
	}
}
