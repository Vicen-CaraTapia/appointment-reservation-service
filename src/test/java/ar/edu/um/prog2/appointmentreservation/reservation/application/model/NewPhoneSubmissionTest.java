package ar.edu.um.prog2.appointmentreservation.reservation.application.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NewPhoneSubmissionTest {
	@ParameterizedTest
	@ValueSource(strings = {"0000000", "+0000000", "000000000000000", "+000000000000000"})
	void acceptsOnlyAlreadyNormalizedContractualBoundaries(String phone) {
		var command = new NewPhoneSubmission(1, "backend-event", phone, "test-project");
		assertThat(command.normalizedPhone()).isEqualTo(phone);
		assertThat(command.toString()).isEqualTo("NewPhoneSubmission[redacted]");
	}

	@ParameterizedTest
	@ValueSource(strings = {"", " ", "000000", "0000000000000000", "00 00000", "00-00000", "(0000000)", "000.0000",
			"++0000000", "0000000+", "abcdefg", "０００００００"})
	void rejectsNoncanonicalOrInvalidPhonesWithoutEchoingValues(String phone) {
		assertThatThrownBy(() -> new NewPhoneSubmission(1, "backend-event", phone, "test-project"))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("Valid backend submission metadata and a normalized phone are required");
	}

	@Test
	void backendIdentityAndPhoneAreRequiredAndOutputDoesNotExposePayload() {
		assertThatThrownBy(() -> new NewPhoneSubmission(0, "backend-event", "0000000", "test-project"))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new NewPhoneSubmission(1, " ", "0000000", "test-project"))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new NewPhoneSubmission(1, "backend-event", null, "test-project"))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new NewPhoneSubmission(1, "backend-event", "0000000", null))
				.isInstanceOf(IllegalArgumentException.class);
		var output = new PhoneSubmissionIntent("backend-event", 1, "request", "process", "hold", "test-project", "0000000",
				java.time.Instant.EPOCH, PhoneSubmissionIntent.State.PENDING);
		assertThat(output.toString()).isEqualTo("PhoneSubmissionIntent[redacted]");
	}
}
