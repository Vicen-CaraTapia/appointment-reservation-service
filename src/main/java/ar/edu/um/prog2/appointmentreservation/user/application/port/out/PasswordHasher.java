package ar.edu.um.prog2.appointmentreservation.user.application.port.out;

public interface PasswordHasher {

	String hash(String password);
}
