package ar.edu.um.prog2.appointmentreservation.user.infrastructure.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.crypto.password.PasswordEncoder;

import ar.edu.um.prog2.appointmentreservation.user.infrastructure.persistence.repository.UserJpaRepository;

@Configuration(proxyBeanMethods = false)
public class FinalUserAuthenticationConfiguration {

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
}
