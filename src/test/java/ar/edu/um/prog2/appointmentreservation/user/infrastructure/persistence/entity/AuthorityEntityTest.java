package ar.edu.um.prog2.appointmentreservation.user.infrastructure.persistence.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class AuthorityEntityTest {

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = { " ", "\t", "\n" })
	void rejectsMissingOrBlankNames(String name) {
		assertThatThrownBy(() -> new AuthorityEntity(name))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("Authority name must contain 1 to 50 characters");
	}

	@Test
	void rejectsNamesThatExceedTheDatabaseColumn() {
		assertThatThrownBy(() -> new AuthorityEntity("R".repeat(51)))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void acceptsTheNameLengthBoundariesWithoutChangingTheIdentity() {
		assertThat(new AuthorityEntity("R").getName()).isEqualTo("R");
		assertThat(new AuthorityEntity("R".repeat(50)).getName()).hasSize(50);
	}

	@Test
	void equalityAndHashingUseTheAuthorityName() {
		AuthorityEntity user = new AuthorityEntity("ROLE_USER");
		AuthorityEntity sameRole = new AuthorityEntity("ROLE_USER");
		AuthorityEntity differentRole = new AuthorityEntity("ROLE_ADMIN");
		assertThat(user.equals(user)).isTrue();
		assertThat(user.equals(sameRole)).isTrue();
		assertThat(sameRole.equals(user)).isTrue();
		assertThat(user.hashCode()).isEqualTo(sameRole.hashCode());
		assertThat(user.equals(differentRole)).isFalse();
		assertThat(user.equals(null)).isFalse();
		assertThat(user.equals("ROLE_USER")).isFalse();
		Set<AuthorityEntity> roles = new HashSet<>();
		roles.add(user);
		roles.add(sameRole);
		roles.add(differentRole);
		assertThat(roles).hasSize(2).contains(user, differentRole);
	}
}
