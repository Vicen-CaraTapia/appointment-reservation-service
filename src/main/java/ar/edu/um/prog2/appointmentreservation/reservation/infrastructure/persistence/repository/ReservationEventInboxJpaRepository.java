package ar.edu.um.prog2.appointmentreservation.reservation.infrastructure.persistence.repository;

import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import ar.edu.um.prog2.appointmentreservation.reservation.infrastructure.persistence.entity.ReservationEventInboxEntity;
import ar.edu.um.prog2.appointmentreservation.reservation.application.model.ReservationEventReceipt.State;

public interface ReservationEventInboxJpaRepository extends JpaRepository<ReservationEventInboxEntity, String> {
	@Modifying
	@Query(value = """
			INSERT INTO {h-schema}reservation_event_inbox (event_id, external_process_id, payload, fingerprint)
			VALUES (:id, :process, :payload, :fingerprint) ON CONFLICT (event_id) DO NOTHING
			""", nativeQuery = true)
	int insertIfAbsent(@Param("id") String id, @Param("process") String externalProcessId,
			@Param("payload") String payload, @Param("fingerprint") String fingerprint);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select e from ReservationEventInboxEntity e where e.eventId = :id")
	Optional<ReservationEventInboxEntity> findForUpdate(@Param("id") String id);

	@Query("select e.eventId from ReservationEventInboxEntity e where e.externalProcessId = :process "
			+ "and e.state = :state "
			+ "and e.contentConflict = false order by e.createdDate, e.eventId")
	List<String> findPending(@Param("process") String externalProcessId, @Param("state") State state, Pageable page);
}
