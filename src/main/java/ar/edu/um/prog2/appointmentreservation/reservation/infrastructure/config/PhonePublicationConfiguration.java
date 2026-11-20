package ar.edu.um.prog2.appointmentreservation.reservation.infrastructure.config;

import java.time.Clock;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import ar.edu.um.prog2.appointmentreservation.reservation.application.port.out.PhoneSubmissionStore;
import ar.edu.um.prog2.appointmentreservation.reservation.application.port.out.PhoneSubmissionPublisher;
import ar.edu.um.prog2.appointmentreservation.reservation.application.usecase.PreparePhoneSubmissionUseCase;
import ar.edu.um.prog2.appointmentreservation.reservation.application.usecase.SubmitPhoneInformationUseCase;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PhonePublicationProperties.class)
@EnableScheduling
public class PhonePublicationConfiguration {
	@Bean
	public SubmitPhoneInformationUseCase submitPhoneInformationUseCase(PreparePhoneSubmissionUseCase prepare,
			PhoneSubmissionStore submissions, PhoneSubmissionPublisher publisher, PhonePublicationProperties settings) {
		return new SubmitPhoneInformationUseCase(prepare, submissions, publisher, Clock.systemUTC(), settings.maxAttempts(),
				settings.lease(), settings.acknowledgmentTimeout(), settings.retryDelay());
	}

	@Bean
	@ConditionalOnProperty(name = "appointment.phone-publication.enabled", havingValue = "true", matchIfMissing = true)
	public RecoveryWorker phonePublicationRecoveryWorker(SubmitPhoneInformationUseCase submit, PhonePublicationProperties settings) {
		return new RecoveryWorker(submit, settings.batchSize());
	}

	public static final class RecoveryWorker {
		private final SubmitPhoneInformationUseCase submit;
		private final int batchSize;
		RecoveryWorker(SubmitPhoneInformationUseCase submit, int batchSize) { this.submit = submit; this.batchSize = batchSize; }
		@Scheduled(initialDelay = 0, fixedDelayString = "${appointment.phone-publication.poll-interval:10s}")
		public void recover() {
			var log = LoggerFactory.getLogger(RecoveryWorker.class);
			try {
				var result = submit.recover(batchSize);
				if (result.scanned() > 0) {
					log.info("Phone publication recovery: scanned={}, published={}, deferred={}",
							result.scanned(), result.published(), result.deferred());
				}
			} catch (RuntimeException failure) { log.warn("Phone publication recovery unavailable"); }
		}
	}
}
