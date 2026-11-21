package ar.edu.um.prog2.appointmentreservation.reservation.infrastructure.persistence.repository;

import java.util.Optional;
import ar.edu.um.prog2.appointmentreservation.reservation.application.model.StoredReservationProgress;
import ar.edu.um.prog2.appointmentreservation.reservation.application.model.ReservationEventReceipt;
import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import ar.edu.um.prog2.appointmentreservation.reservation.infrastructure.persistence.entity.ReservationProcessEntity;

public interface ReservationProcessJpaRepository extends JpaRepository<ReservationProcessEntity, Long> {

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select p from ReservationProcessEntity p where p.id = :id")
	Optional<ReservationProcessEntity> findByIdForUpdate(@Param("id") long id);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select p from ReservationProcessEntity p where p.id = :id and p.owner.id = :owner")
	Optional<ReservationProcessEntity> findOwnedForUpdate(@Param("id") long id, @Param("owner") long ownerId);

	Optional<ReservationProcessEntity> findByIdAndOwner_Id(long id, long ownerId);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select p from ReservationProcessEntity p where p.externalReservationProcessId = :externalId")
	Optional<ReservationProcessEntity> findByExternalIdForUpdate(@Param("externalId") String externalId);
	@Query("select new ar.edu.um.prog2.appointmentreservation.reservation.application.model.StoredReservationProgress("
			+ "p.id, p.status, p.expiresAt, "
			+ "case when r.eventId is not null and r.localProcessId = p.id and r.state = :applied "
			+ "and r.contentConflict = false then true else false end, p.outcomeReason) "
			+ "from ReservationProcessEntity p left join ReservationEventInboxEntity r on r.eventId = p.requestEventId "
			+ "where p.id = :id and p.owner.id = :owner")
	Optional<StoredReservationProgress> findOwnedProgress(@Param("id") long id, @Param("owner") long owner,
			@Param("applied") ReservationEventReceipt.State applied);

}
