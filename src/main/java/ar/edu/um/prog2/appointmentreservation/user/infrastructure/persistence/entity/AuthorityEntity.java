package ar.edu.um.prog2.appointmentreservation.user.infrastructure.persistence.entity;

import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "authority")
public class AuthorityEntity {

	@Id
	@Column(name = "name", length = 50, nullable = false)
	private String name;

	protected AuthorityEntity() {
	}

	public AuthorityEntity(String name) {
		if (name == null || name.isBlank() || name.length() > 50) {
			throw new IllegalArgumentException("Authority name must contain 1 to 50 characters");
		}
		this.name = name;
	}

	public String getName() {
		return name;
	}

	@Override
	public boolean equals(Object other) {
		return this == other || other instanceof AuthorityEntity authority
				&& Objects.equals(getName(), authority.getName());
	}

	@Override
	public int hashCode() {
		return Objects.hashCode(getName());
	}
}
