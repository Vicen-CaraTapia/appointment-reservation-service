package ar.edu.um.prog2.appointmentreservation.user.infrastructure.persistence.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import ar.edu.um.prog2.appointmentreservation.user.infrastructure.security.PasswordEncodingConfiguration;

class UserEntityTest {

	private static final String ENCODED_PASSWORD = new PasswordEncodingConfiguration()
			.passwordEncoder().encode("test-only-entity-passphrase");

	@Test
	void normalizesIdentifiersAndKeepsInternalDefaults() {
		UserEntity user = user(ENCODED_PASSWORD);

		assertThat(user.getLogin()).isEqualTo("alice");
		assertThat(user.getEmail()).isEqualTo("alice@example.test");
		assertThat(user.getId()).isNull();
		assertThat(user.getCreatedDate()).isNull();
		assertThat(user.isActivated()).isFalse();
		assertThat(user.getAuthorities()).isEmpty();
		assertThat(user.getPasswordHash()).isEqualTo(ENCODED_PASSWORD);
	}

	@Test
	void rejectsPlaintextInsteadOfEncodingItInsideTheEntity() {
		assertThatThrownBy(() -> user("test-only-plaintext"))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("An encoded Argon2id hash is required");
	}

	@Test
	void doesNotExposeAMutableAuthorityCollection() {
		UserEntity user = user(ENCODED_PASSWORD);
		AuthorityEntity authority = new AuthorityEntity("ROLE_USER");
		assertThatThrownBy(() -> user.getAuthorities().add(authority))
				.isInstanceOf(UnsupportedOperationException.class);
		user.grantAuthority(authority, "backend-test");
		user.grantAuthority(new AuthorityEntity("ROLE_USER"), "backend-test");
		assertThat(user.getAuthorities()).containsExactly(authority);
	}

	@Test
	void activationRequiresAnExplicitBackendOperation() {
		UserEntity user = user(ENCODED_PASSWORD);
		assertThatThrownBy(() -> user.activate(" ")).isInstanceOf(IllegalArgumentException.class);
		assertThat(user.isActivated()).isFalse();
		user.activate("backend-test");
		assertThat(user.isActivated()).isTrue();
	}

	@Test
	void updatesProfileWithoutChangingIdentityHashOrActivation() {
		UserEntity user = user(ENCODED_PASSWORD);
		user.updateProfile("Updated", null, "UPDATED@example.test", null, "en", "backend-editor");
		assertThat(user.getFirstName()).isEqualTo("Updated");
		assertThat(user.getLastName()).isNull();
		assertThat(user.getEmail()).isEqualTo("updated@example.test");
		assertThat(user.getImageUrl()).isNull();
		assertThat(user.getLangKey()).isEqualTo("en");
		assertThat(user.getLogin()).isEqualTo("alice");
		assertThat(user.getPasswordHash()).isEqualTo(ENCODED_PASSWORD);
		assertThat(user.isActivated()).isFalse();
		assertThat(user.getCreatedBy()).isEqualTo("backend-test");
	}

	@Test
	void rejectsAnInvalidProfileUpdateWithoutPartiallyChangingTheEntity() {
		UserEntity user = user(ENCODED_PASSWORD);
		assertThatThrownBy(() -> user.updateProfile("Updated", "Name", "new@example.test", null,
				"en", " ")).isInstanceOf(IllegalArgumentException.class);
		assertThat(user.getFirstName()).isEqualTo("Alice");
		assertThat(user.getEmail()).isEqualTo("alice@example.test");
		assertThat(user.getLangKey()).isEqualTo("es");
	}

	@Test
	void doesNotIncludePasswordMaterialInItsStringRepresentation() {
		assertThat(user(ENCODED_PASSWORD).toString())
				.doesNotContain(ENCODED_PASSWORD, "test-only-entity-passphrase");
	}

	private static UserEntity user(String encodedPassword) {
		return new UserEntity("Alice", encodedPassword, "Alice", "Example",
				"ALICE@example.test", null, "es", "backend-test");
	}
}
