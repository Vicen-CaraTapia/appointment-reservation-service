package ar.edu.um.prog2.appointmentreservation.user.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import ar.edu.um.prog2.appointmentreservation.user.application.port.out.PasswordHasher;
import ar.edu.um.prog2.appointmentreservation.user.infrastructure.security.FinalUserSecurityConfiguration;

class UserRegistrationConfigurationTest {

	@Test
	void hashingPortUsesTheExistingArgon2idEncoder() {
		PasswordEncoder encoder = new FinalUserSecurityConfiguration().passwordEncoder();
		PasswordHasher hasher = new UserRegistrationConfiguration().passwordHasher(encoder);
		String encoded = hasher.hash("  test-only passphrase  ");
		assertThat(encoded).startsWith("$argon2id$v=19$m=19456,t=2,p=1$");
		assertThat(encoder.matches("  test-only passphrase  ", encoded)).isTrue();
		assertThat(encoder.matches("test-only passphrase", encoded)).isFalse();
	}
}
