package ar.edu.um.prog2.appointmentreservation.user.application.exception;

import java.util.Objects;

public final class DuplicateUserException extends RuntimeException {

	public enum Field { LOGIN, EMAIL }

	private final Field field;

	public DuplicateUserException(Field field) {
		super(Objects.requireNonNull(field) == Field.LOGIN ? "Login already exists" : "Email already exists");
		this.field = field;
	}

	public Field field() {
		return field;
	}
}
