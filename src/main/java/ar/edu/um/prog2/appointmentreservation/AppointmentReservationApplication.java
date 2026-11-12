package ar.edu.um.prog2.appointmentreservation;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

import ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.config.CatedraIntegrationProperties;
import ar.edu.um.prog2.appointmentreservation.integration.catalog.infrastructure.config.CatalogIntegrationProperties;

@SpringBootApplication
@ConfigurationPropertiesScan(basePackageClasses = {CatedraIntegrationProperties.class, CatalogIntegrationProperties.class})
public class AppointmentReservationApplication {

	public static void main(String[] args) {
		SpringApplication.run(AppointmentReservationApplication.class, args);
	}

}
