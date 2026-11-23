package ar.edu.um.prog2.appointmentreservation.reservation.infrastructure.persistence;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;
import ar.edu.um.prog2.appointmentreservation.reservation.application.exception.OwnedReservationQueryException;
import ar.edu.um.prog2.appointmentreservation.reservation.application.model.TechnicalReservation;
import ar.edu.um.prog2.appointmentreservation.reservation.domain.model.ReservationProcessStatus;
import ar.edu.um.prog2.appointmentreservation.reservation.infrastructure.persistence.entity.ReservationProcessEntity;
import ar.edu.um.prog2.appointmentreservation.user.infrastructure.persistence.entity.UserEntity;
import static org.assertj.core.api.Assertions.*;

class ReservationRestEvidenceTest {
	private static final Instant START = Instant.parse("2026-11-22T12:00:00Z");
	private static final LocalDate DATE = LocalDate.of(2026,12,1);

	@Test void restConfirmationNeedsNoInventedEventPatientNameOrPhone() {
		var process = held();
		process.reconcileRest(row(TechnicalReservation.Status.CONFIRMED),START,START.plusSeconds(1),"alice");
		assertThat(process.getStatus()).isEqualTo(ReservationProcessStatus.CONFIRMED);
		assertThat(process.getExternalReservationId()).isEqualTo(8251L);
		assertThat(process.getRequestEventId()).isNull(); assertThat(process.getPatientFirstName()).isNull();
		assertThat(process.getRestObservedStatus().name()).isEqualTo("CONFIRMED");
		assertThat(process.getRestVerifiedAt()).isEqualTo(START.plusSeconds(1));
		assertThat(process.getLastModifiedBy()).isEqualTo("alice");
	}

	@Test void pendingEvidenceDoesNotEnablePhoneSubmissionOrRewriteExpiry() {
		var process = held(); var expiry = process.getExpiresAt();
		process.reconcileRest(row(TechnicalReservation.Status.PHONE_PENDING),START,START.plusSeconds(1),"alice");
		assertThat(process.getStatus()).isEqualTo(ReservationProcessStatus.HELD);
		assertThat(process.getRequestEventId()).isNull(); assertThat(process.getExpiresAt()).isEqualTo(expiry);
		assertThatThrownBy(() -> process.preparePhoneSubmission("new-event","alice")).isInstanceOf(IllegalStateException.class);
	}

	@Test void genericFailureDoesNotInventAnExpiryOrInvalidProcessReason() {
		var process = held();
		process.reconcileRest(row(TechnicalReservation.Status.FAILED),START,START.plusSeconds(1),"alice");
		assertThat(process.getStatus()).isEqualTo(ReservationProcessStatus.FAILED);
		assertThat(process.getOutcomeReason()).isNull();
	}

	@Test void oldRestResponseCannotOverwriteLocalChangesDuringTheRequest() {
		var process = held();
		ReflectionTestUtils.setField(process,"lastModifiedDate",START.plusSeconds(1));
		assertThatThrownBy(() -> process.reconcileRest(row(TechnicalReservation.Status.CONFIRMED),START,START.plusSeconds(2),"alice"))
				.isInstanceOfSatisfying(OwnedReservationQueryException.class,error -> assertThat(error.reason()).isEqualTo(OwnedReservationQueryException.Reason.CONCURRENT_CHANGE));
		assertThat(process.getStatus()).isEqualTo(ReservationProcessStatus.HELD);
		assertThat(process.getRestVerifiedAt()).isNull();
	}

	@ParameterizedTest @ValueSource(strings={"process","patient","reservation","professional","date","time"})
	void correlationCannotBeBypassedAtThePersistenceBoundary(String mismatch) {
		var process = held(); var original = row(TechnicalReservation.Status.CONFIRMED);
		var changed = new TechnicalReservation(mismatch.equals("reservation")?99:8251,
				mismatch.equals("process")?"other-process":"process-rest",mismatch.equals("patient")?"user-43":"user-42",
				mismatch.equals("date")?DATE.plusDays(1):DATE,mismatch.equals("time")?LocalTime.MIDNIGHT:LocalTime.NOON,
				original.endTime(),mismatch.equals("professional")?203:202,null,null,null,null,null,original.status(),original.createdAt(),null,null,null);
		if(mismatch.equals("reservation")) { ReflectionTestUtils.setField(process,"externalReservationId",8251L); }
		assertThatThrownBy(() -> process.reconcileRest(changed,START,START.plusSeconds(1),"alice"))
				.isInstanceOf(OwnedReservationQueryException.class).hasNoCause();
		assertThat(process.getRestVerifiedAt()).isNull();
	}

	private static ReservationProcessEntity held() {
		var owner = new UserEntity("alice","$argon2id$v=19$m=19456,t=2,p=1$testSalt$testHash",null,null,"alice@example.test",null,"es","test");
		ReflectionTestUtils.setField(owner,"id",42L);
		var process = new ReservationProcessEntity(owner,202,DATE,LocalTime.NOON,"user-42","alice");
		process.markHeld("hold-rest","process-rest",START.plusSeconds(300),"alice");
		ReflectionTestUtils.setField(process,"createdDate",START.minusSeconds(1));
		return process;
	}
	private static TechnicalReservation row(TechnicalReservation.Status status) {
		return new TechnicalReservation(8251,"process-rest","user-42",DATE,LocalTime.NOON,LocalTime.of(12,30),202,
				"Private name",null,"Private patient",null,"+5492615550000",status,START.minusSeconds(60),null,null,null);
	}
}
