package ar.edu.um.prog2.appointmentreservation.reservation.infrastructure.persistence.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import ar.edu.um.prog2.appointmentreservation.reservation.infrastructure.persistence.entity.ReservationProcessEntity;

public interface ReservationProcessJpaRepository extends JpaRepository<ReservationProcessEntity, Long> {

	Optional<ReservationProcessEntity> findByIdAndOwner_Id(long id, long ownerId);
}
