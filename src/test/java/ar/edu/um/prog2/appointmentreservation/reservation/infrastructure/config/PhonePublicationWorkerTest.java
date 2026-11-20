package ar.edu.um.prog2.appointmentreservation.reservation.infrastructure.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import ar.edu.um.prog2.appointmentreservation.reservation.application.usecase.SubmitPhoneInformationUseCase;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(OutputCaptureExtension.class)
class PhonePublicationWorkerTest {
	@Test
	void invokesOnlyOneBoundedPassAndLogsCountsWithoutPayload(CapturedOutput output) {
		var submit = mock(SubmitPhoneInformationUseCase.class);
		when(submit.recover(10)).thenReturn(new SubmitPhoneInformationUseCase.RecoveryResult(3, 1, 2));
		new PhonePublicationConfiguration.RecoveryWorker(submit, 10).recover();
		verify(submit, times(1)).recover(10);
		assertThat(output.getAll()).contains("scanned=3, published=1, deferred=2");
	}
	@Test
	void failureDoesNotLeakPrivateDiagnosticsOrStartAnInternalRetryLoop(CapturedOutput output) {
		var submit = mock(SubmitPhoneInformationUseCase.class);
		when(submit.recover(10)).thenThrow(new IllegalStateException("private-phone-1234567"));
		assertThatCode(() -> new PhonePublicationConfiguration.RecoveryWorker(submit, 10).recover()).doesNotThrowAnyException();
		verify(submit, times(1)).recover(10);
		assertThat(output.getAll()).contains("Phone publication recovery unavailable").doesNotContain("private-phone-1234567");
	}
}
