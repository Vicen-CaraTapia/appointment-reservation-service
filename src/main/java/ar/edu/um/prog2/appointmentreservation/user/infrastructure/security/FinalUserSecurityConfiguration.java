package ar.edu.um.prog2.appointmentreservation.user.infrastructure.security;

import java.time.Clock;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import ar.edu.um.prog2.appointmentreservation.user.application.port.out.FinalUserAuthenticator;
import ar.edu.um.prog2.appointmentreservation.user.application.usecase.AuthenticateUserUseCase;
import ar.edu.um.prog2.appointmentreservation.user.infrastructure.persistence.repository.UserJpaRepository;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(FinalUserJwtProperties.class)
public class FinalUserSecurityConfiguration {

	@Bean
	public PasswordEncoder passwordEncoder() {
		// Shared by registration and authentication; bytes for salt/hash, KiB for memory.
		return new Argon2PasswordEncoder(16, 32, 1, 19456, 2);
	}

	@Bean
	public FinalUserDetailsService finalUserDetailsService(UserJpaRepository users) {
		return new FinalUserDetailsService(users);
	}

	@Bean
	public AuthenticationManager finalUserAuthenticationManager(FinalUserDetailsService users, PasswordEncoder encoder) {
		DaoAuthenticationProvider provider = new DaoAuthenticationProvider(users);
		provider.setPasswordEncoder(encoder);
		provider.setHideUserNotFoundExceptions(true);
		ProviderManager manager = new ProviderManager(provider);
		manager.setEraseCredentialsAfterAuthentication(true);
		return manager;
	}

	@Bean
	public NimbusFinalUserTokenIssuer finalUserTokenIssuer(FinalUserJwtProperties properties) {
		// Eager singleton construction validates the key and TTLs during startup.
		return new NimbusFinalUserTokenIssuer(properties, Clock.systemUTC());
	}

	@Bean
	public FinalUserAuthenticator finalUserAuthenticator(AuthenticationManager manager, NimbusFinalUserTokenIssuer tokens) {
		return new SpringFinalUserAuthenticator(manager, tokens);
	}

	@Bean
	public AuthenticateUserUseCase authenticateUserUseCase(FinalUserAuthenticator authenticator) {
		return new AuthenticateUserUseCase(authenticator);
	}
}
