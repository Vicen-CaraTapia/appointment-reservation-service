package ar.edu.um.prog2.appointmentreservation;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

import ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.config.CatedraIntegrationProperties;

@SpringBootApplication
@ConfigurationPropertiesScan(basePackageClasses = CatedraIntegrationProperties.class)
public class AppointmentReservationApplication {

	public static void main(String[] args) {
		SpringApplication.run(AppointmentReservationApplication.class, args);
	}

}
