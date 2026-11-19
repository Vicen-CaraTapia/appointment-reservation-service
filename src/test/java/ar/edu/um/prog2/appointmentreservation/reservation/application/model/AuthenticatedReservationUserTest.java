package ar.edu.um.prog2.appointmentreservation.reservation.application.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import ar.edu.um.prog2.appointmentreservation.reservation.application.ExternalPatientId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthenticatedReservationUserTest {

	@ParameterizedTest
	@ValueSource(longs = {1, 42, Long.MAX_VALUE})
	void suppliesTheStableUserIdentityWithoutCarryingCredentials(long userId) {
		var user = new AuthenticatedReservationUser(userId, "final-user");
		assertThat(ExternalPatientId.fromUserId(user.userId())).isEqualTo("user-" + userId);
		assertThat(user.login()).isEqualTo("final-user");
		assertThat(user.toString()).isEqualTo("AuthenticatedReservationUser[redacted]");
	}

	@ParameterizedTest
	@ValueSource(longs = {0, -1, Long.MIN_VALUE})
	void rejectsAnIdentityThatCannotOwnAPersistedProcess(long id) {
		assertThatThrownBy(() -> new AuthenticatedReservationUser(id, "private-login"))
				.isInstanceOf(IllegalArgumentException.class).hasMessageNotContaining("private-login").hasNoCause();
	}

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = {" ", "\t", "\n"})
	void rejectsMissingOrBlankAuditLogin(String login) {
		assertThatThrownBy(() -> new AuthenticatedReservationUser(1, login))
				.isInstanceOf(IllegalArgumentException.class).hasNoCause();
	}

	@Test
	void alignsTheAuditLoginLengthWithTheExistingUserAndPersistenceContract() {
		assertThat(new AuthenticatedReservationUser(1, "a".repeat(50)).login()).hasSize(50);
		String tooLong = "private-login-" + "x".repeat(50);
		assertThatThrownBy(() -> new AuthenticatedReservationUser(1, tooLong))
				.isInstanceOf(IllegalArgumentException.class).hasMessageNotContaining(tooLong).hasNoCause();
	}
}
