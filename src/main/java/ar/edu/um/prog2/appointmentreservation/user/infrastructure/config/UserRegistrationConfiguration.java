package ar.edu.um.prog2.appointmentreservation.user.infrastructure.config;

import jakarta.persistence.EntityManagerFactory;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;

import ar.edu.um.prog2.appointmentreservation.user.application.port.out.PasswordHasher;
import ar.edu.um.prog2.appointmentreservation.user.application.port.out.UserRegistrationStore;
import ar.edu.um.prog2.appointmentreservation.user.application.usecase.RegisterUserUseCase;
import ar.edu.um.prog2.appointmentreservation.user.infrastructure.persistence.JpaUserRegistrationAdapter;
import ar.edu.um.prog2.appointmentreservation.user.infrastructure.persistence.repository.UserJpaRepository;

@Configuration(proxyBeanMethods = false)
public class UserRegistrationConfiguration {

	@Bean
	public PasswordHasher passwordHasher(PasswordEncoder encoder) {
		return encoder::encode;
	}

	@Bean
	public UserRegistrationStore userRegistrationStore(UserJpaRepository users,
			EntityManagerFactory entityManagerFactory, PlatformTransactionManager transactionManager) {
		return new JpaUserRegistrationAdapter(users,
				SharedEntityManagerCreator.createSharedEntityManager(entityManagerFactory), transactionManager);
	}

	@Bean
	public RegisterUserUseCase registerUserUseCase(UserRegistrationStore users, PasswordHasher passwords) {
		return new RegisterUserUseCase(users, passwords);
	}
}
