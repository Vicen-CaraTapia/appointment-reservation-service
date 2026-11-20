package ar.edu.um.prog2.appointmentreservation.reservation.infrastructure.persistence.repository;

import java.util.Optional;
import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import ar.edu.um.prog2.appointmentreservation.reservation.infrastructure.persistence.entity.ReservationProcessEntity;

public interface ReservationProcessJpaRepository extends JpaRepository<ReservationProcessEntity, Long> {

	Optional<ReservationProcessEntity> findByIdAndOwner_Id(long id, long ownerId);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select p from ReservationProcessEntity p where p.externalReservationProcessId = :externalId")
	Optional<ReservationProcessEntity> findByExternalIdForUpdate(@Param("externalId") String externalId);
}
