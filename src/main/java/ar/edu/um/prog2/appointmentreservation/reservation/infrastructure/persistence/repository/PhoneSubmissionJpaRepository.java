package ar.edu.um.prog2.appointmentreservation.reservation.infrastructure.persistence.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import ar.edu.um.prog2.appointmentreservation.reservation.application.model.PhoneSubmissionIntent.State;
import ar.edu.um.prog2.appointmentreservation.reservation.application.model.ReservationEventReceipt;
import ar.edu.um.prog2.appointmentreservation.reservation.domain.model.ReservationProcessStatus;
import ar.edu.um.prog2.appointmentreservation.reservation.infrastructure.persistence.entity.PhoneSubmissionEntity;

public interface PhoneSubmissionJpaRepository extends JpaRepository<PhoneSubmissionEntity, String> {
	@Query("select s.localProcessId from PhoneSubmissionEntity s where s.eventId = :id")
	Optional<Long> findProcessId(@Param("id") String eventId);
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select s from PhoneSubmissionEntity s where s.eventId = :id")
	Optional<PhoneSubmissionEntity> findForUpdate(@Param("id") String eventId);

	@Query("select s.eventId from PhoneSubmissionEntity s, ReservationProcessEntity p, ReservationEventInboxEntity r "
			+ "where s.localProcessId = p.id and p.currentPhoneSubmissionEventId = s.eventId "
			+ "and s.requestEventId = r.eventId and r.localProcessId = p.id and r.state = :applied and r.contentConflict = false "
			+ "and s.state = :pending and p.status = :phase and p.expiresAt > :now order by s.createdDate, s.eventId")
	List<String> findPending(@Param("now") Instant now, @Param("pending") State pending,
			@Param("phase") ReservationProcessStatus phase, @Param("applied") ReservationEventReceipt.State applied, Pageable page);
}
