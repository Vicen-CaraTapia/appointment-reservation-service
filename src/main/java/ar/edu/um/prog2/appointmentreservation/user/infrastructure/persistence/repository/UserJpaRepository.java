package ar.edu.um.prog2.appointmentreservation.user.infrastructure.persistence.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import ar.edu.um.prog2.appointmentreservation.user.infrastructure.persistence.entity.UserEntity;

public interface UserJpaRepository extends JpaRepository<UserEntity, Long> {

	@EntityGraph(attributePaths = "authorities")
	Optional<UserEntity> findByLoginIgnoreCase(String login);
}
