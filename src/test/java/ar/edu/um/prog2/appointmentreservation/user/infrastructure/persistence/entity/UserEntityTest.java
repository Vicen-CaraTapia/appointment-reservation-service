package ar.edu.um.prog2.appointmentreservation.user.infrastructure.persistence.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import ar.edu.um.prog2.appointmentreservation.user.infrastructure.security.FinalUserSecurityConfiguration;

class UserEntityTest {

	private static final String ENCODED_PASSWORD = new FinalUserSecurityConfiguration()
			.passwordEncoder().encode("test-only-entity-passphrase");

	@Test
	void normalizesIdentifiersAndKeepsInternalDefaults() {
		UserEntity user = user(ENCODED_PASSWORD);

		assertThat(user.getLogin()).isEqualTo("alice");
		assertThat(user.getEmail()).isEqualTo("alice@example.test");
		assertThat(user.getId()).isNull();
		assertThat(user.getCreatedDate()).isNull();
		assertThat(user.getLastModifiedBy()).isNull();
		assertThat(user.getLastModifiedDate()).isNull();
		assertThat(user.isActivated()).isFalse();
		assertThat(user.getAuthorities()).isEmpty();
		assertThat(user.getPasswordHash()).isEqualTo(ENCODED_PASSWORD);
	}

	@Test
	void acceptsNullableProfileFieldsAndTheStorageLengthBoundaries() {
		UserEntity nullable = new UserEntity("a", ENCODED_PASSWORD, null, null,
				"a@b.c", null, "es", "a");
		assertThat(nullable.getFirstName()).isNull();
		assertThat(nullable.getLastName()).isNull();
		assertThat(nullable.getImageUrl()).isNull();

		UserEntity maximum = new UserEntity("a".repeat(50), ENCODED_PASSWORD,
				"f".repeat(50), "l".repeat(50), "a".repeat(241) + "@example.test",
				"i".repeat(256), "l".repeat(10), "a".repeat(50));
		assertThat(maximum.getLogin()).hasSize(50);
		assertThat(maximum.getFirstName()).hasSize(50);
		assertThat(maximum.getLastName()).hasSize(50);
		assertThat(maximum.getEmail()).hasSize(254);
		assertThat(maximum.getImageUrl()).hasSize(256);
		assertThat(maximum.getLangKey()).hasSize(10);
		assertThat(maximum.getCreatedBy()).hasSize(50);
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("invalidCreationFields")
	void rejectsInvalidCreationFields(String description, int field, String invalidValue) {
		String[] values = { "alice", ENCODED_PASSWORD, "Alice", "Example",
				"alice@example.test", null, "es", "backend-test" };
		values[field] = invalidValue;
		assertThatThrownBy(() -> new UserEntity(values[0], values[1], values[2], values[3],
				values[4], values[5], values[6], values[7]))
				.as(description)
				.isInstanceOf(IllegalArgumentException.class);
	}

	private static Stream<Arguments> invalidCreationFields() {
		return Stream.of(
				Arguments.of("missing login", 0, null),
				Arguments.of("blank login", 0, " "),
				Arguments.of("login longer than column", 0, "a".repeat(51)),
				Arguments.of("missing encoded hash", 1, null),
				Arguments.of("encoded hash longer than column", 1, "x".repeat(256)),
				Arguments.of("first name longer than column", 2, "a".repeat(51)),
				Arguments.of("last name longer than column", 3, "a".repeat(51)),
				Arguments.of("missing email", 4, null),
				Arguments.of("blank email", 4, " "),
				Arguments.of("email shorter than minimum", 4, "a@b"),
				Arguments.of("email longer than column", 4, "a".repeat(255)),
				Arguments.of("image URL longer than column", 5, "i".repeat(257)),
				Arguments.of("missing language", 6, null),
				Arguments.of("blank language", 6, " "),
				Arguments.of("language shorter than minimum", 6, "e"),
				Arguments.of("language longer than column", 6, "e".repeat(11)),
				Arguments.of("missing audit actor", 7, null),
				Arguments.of("blank audit actor", 7, " "),
				Arguments.of("audit actor longer than column", 7, "a".repeat(51)));
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
