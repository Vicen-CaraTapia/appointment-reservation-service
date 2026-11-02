package ar.edu.um.prog2.appointmentreservation.user.infrastructure.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration(proxyBeanMethods = false)
public class PasswordEncodingConfiguration {

	@Bean
	public PasswordEncoder passwordEncoder() {
		// Salt/hash lengths are bytes; memory is KiB, not bytes.
		return new Argon2PasswordEncoder(16, 32, 1, 19456, 2);
	}
}
