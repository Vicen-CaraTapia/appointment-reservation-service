package ar.edu.um.prog2.appointmentreservation.reservation.application.usecase;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;

import ar.edu.um.prog2.appointmentreservation.reservation.application.exception.InvalidPhoneNumberException;
import ar.edu.um.prog2.appointmentreservation.reservation.application.exception.PhoneSubmissionStateException;
import ar.edu.um.prog2.appointmentreservation.reservation.application.exception.ReservationAuthenticationRequiredException;
import ar.edu.um.prog2.appointmentreservation.reservation.application.model.AuthenticatedReservationUser;
import ar.edu.um.prog2.appointmentreservation.reservation.application.model.NewPhoneSubmission;
import ar.edu.um.prog2.appointmentreservation.reservation.application.model.PhoneSubmissionIntent;
import ar.edu.um.prog2.appointmentreservation.reservation.application.port.out.CurrentReservationUser;
import ar.edu.um.prog2.appointmentreservation.reservation.application.port.out.PhoneSubmissionStore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class PreparePhoneSubmissionUseCaseTest {
	private static final Instant NOW = Instant.parse("2030-01-01T12:00:00Z");
	private static final AuthenticatedReservationUser OWNER = new AuthenticatedReservationUser(42, "alice");
	private final CurrentReservationUser currentUser = mock(CurrentReservationUser.class);
	private final PhoneSubmissionStore submissions = mock(PhoneSubmissionStore.class);
	private final PreparePhoneSubmissionUseCase useCase = new PreparePhoneSubmissionUseCase(currentUser,
			submissions, "test-project", Clock.fixed(NOW, ZoneOffset.UTC));

	@ParameterizedTest
	@CsvSource({"1234567,1234567", "+1234567,+1234567", "123456789012345,123456789012345",
		"+123456789012345,+123456789012345", "'+54 (261) 555-12.34',+542615551234",
		"'000 000-0',0000000"})
	void normalizesAndPreparesOnlyWithTrustedOwnerGroupAndBackendEventId(String input, String normalized) {
		when(currentUser.requireAuthenticated()).thenReturn(OWNER);
		var intent = intent();
		when(submissions.prepare(eq(OWNER), any(), eq(NOW))).thenReturn(intent);

		assertThat(useCase.prepare(9, input)).isSameAs(intent);

		var command = ArgumentCaptor.forClass(NewPhoneSubmission.class);
		verify(submissions).prepare(eq(OWNER), command.capture(), eq(NOW));
		assertThat(command.getValue().localProcessId()).isEqualTo(9);
		assertThat(command.getValue().normalizedPhone()).isEqualTo(normalized);
		assertThat(command.getValue().technicalGroupId()).isEqualTo("test-project");
		assertThat(UUID.fromString(command.getValue().eventId())).isNotNull();
		assertThat(command.getValue().toString()).doesNotContain(normalized);
	}

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = {" ", "123456", "1234567890123456", "+123456", "+1234567890123456",
		"++1234567", "12+34567", "1234567x", "123/4567", "１２３４５６７", ".......", "secret-phone-value"})
	void rejectsInvalidPhonesWithoutPersistingOrExposingInput(String input) {
		when(currentUser.requireAuthenticated()).thenReturn(OWNER);
		assertThatThrownBy(() -> useCase.prepare(9, input)).isInstanceOf(InvalidPhoneNumberException.class)
				.hasMessage("A phone number with 7 to 15 digits and an optional leading plus is required")
				.hasNoCause();
		verifyNoInteractions(submissions);
	}

	@Test
	void removesWhitespaceWithoutRemovingOtherUnsupportedCharacters() {
		when(currentUser.requireAuthenticated()).thenReturn(OWNER);
		useCase.prepare(9, "\t123 4567\n");
		verify(submissions).prepare(eq(OWNER), argThat(value -> value.normalizedPhone().equals("1234567")), eq(NOW));
	}

	@Test
	void requiresAuthenticationBeforePreparingAnything() {
		when(currentUser.requireAuthenticated()).thenThrow(new ReservationAuthenticationRequiredException());
		assertThatThrownBy(() -> useCase.prepare(9, "1234567"))
				.isInstanceOf(ReservationAuthenticationRequiredException.class);
		verifyNoInteractions(submissions);
	}

	@ParameterizedTest
	@ValueSource(longs = {0, -1})
	void rejectsInvalidLocalIdentifiers(long id) {
		when(currentUser.requireAuthenticated()).thenReturn(OWNER);
		assertThatThrownBy(() -> useCase.prepare(id, "1234567")).isInstanceOf(IllegalArgumentException.class);
		verifyNoInteractions(submissions);
	}

	@Test
	void repeatedRequestsReturnTheDurableIdentityAndTimestampRatherThanCandidateIds() {
		when(currentUser.requireAuthenticated()).thenReturn(OWNER);
		var existing = intent();
		when(submissions.prepare(eq(OWNER), any(), eq(NOW))).thenReturn(existing);
		assertThat(useCase.prepare(9, "1234567")).isSameAs(existing);
		assertThat(useCase.prepare(9, "123-4567")).isSameAs(existing);
		assertThat(existing.eventId()).isEqualTo("stored-event");
		assertThat(existing.occurredAt()).isEqualTo(NOW.minusSeconds(5));
	}

	@ParameterizedTest
	@EnumSource(PhoneSubmissionStateException.Reason.class)
	void preservesControlledOwnershipExpiryStateAndStorageFailures(PhoneSubmissionStateException.Reason reason) {
		when(currentUser.requireAuthenticated()).thenReturn(OWNER);
		var failure = new PhoneSubmissionStateException(reason);
		when(submissions.prepare(eq(OWNER), any(), eq(NOW))).thenThrow(failure);
		assertThatThrownBy(() -> useCase.prepare(9, "1234567")).isSameAs(failure);
	}

	private static PhoneSubmissionIntent intent() {
		return new PhoneSubmissionIntent("stored-event", 9, "request-original", "external-process", "hold",
				"test-project", "1234567", NOW.minusSeconds(5), PhoneSubmissionIntent.State.PENDING);
	}
}
