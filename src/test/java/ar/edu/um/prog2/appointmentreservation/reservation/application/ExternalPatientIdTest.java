package ar.edu.um.prog2.appointmentreservation.reservation.application;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExternalPatientIdTest {

	@ParameterizedTest
	@ValueSource(longs = {1, 42, Long.MAX_VALUE})
	void derivesAStableBackendIdentifierFromThePersistedUserId(long userId) {
		String first = ExternalPatientId.fromUserId(userId);
		assertThat(first).isEqualTo("user-" + userId).hasSizeLessThanOrEqualTo(100);
		assertThat(ExternalPatientId.fromUserId(userId)).isEqualTo(first);
	}

	@ParameterizedTest
	@ValueSource(longs = {0, -1, Long.MIN_VALUE})
	void rejectsIdsThatCannotRepresentAnAuthenticatedPersistedUser(long userId) {
		assertThatThrownBy(() -> ExternalPatientId.fromUserId(userId)).isInstanceOf(IllegalArgumentException.class);
	}
}
