package ar.edu.um.prog2.appointmentreservation.user.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Base64;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

class PasswordEncodingConfigurationTest {

	private final PasswordEncoder encoder = new FinalUserSecurityConfiguration().passwordEncoder();

	@Test
	void matchesTheCorrectPassword() {
		String encoded = encoder.encode("test-only-passphrase");

		assertThat(encoder.matches("test-only-passphrase", encoded)).isTrue();
	}

	@Test
	void rejectsAnIncorrectPassword() {
		String encoded = encoder.encode("test-only-passphrase");

		assertThat(encoder.matches("different-test-passphrase", encoded)).isFalse();
	}

	@Test
	void generatesDifferentRandomSaltsForIdenticalPasswords() {
		String first = encoder.encode("test-only-passphrase");
		String second = encoder.encode("test-only-passphrase");

		assertThat(first).isNotEqualTo(second);
		assertThat(salt(first)).isNotEqualTo(salt(second));
		assertThat(encoder.matches("test-only-passphrase", first)).isTrue();
		assertThat(encoder.matches("test-only-passphrase", second)).isTrue();
	}

	@Test
	void encodesArgon2idWithTheApprovedParametersAndFitsTheStorageColumn() {
		String encoded = encoder.encode("test-only-passphrase");

		assertThat(encoded).matches(
				"\\$argon2id\\$v=19\\$m=19456,t=2,p=1\\$[A-Za-z0-9+/]{22}\\$[A-Za-z0-9+/]{43}");
		assertThat(encoded.length()).isLessThanOrEqualTo(255);
		assertThat(salt(encoded)).hasSize(16);
		assertThat(Base64.getDecoder().decode(encoded.split("\\$")[5])).hasSize(32);
	}

	private static byte[] salt(String encoded) {
		return Base64.getDecoder().decode(encoded.split("\\$")[4]);
	}
}
