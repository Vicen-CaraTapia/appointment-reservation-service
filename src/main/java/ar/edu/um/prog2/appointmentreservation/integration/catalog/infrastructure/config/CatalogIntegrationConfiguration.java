package ar.edu.um.prog2.appointmentreservation.integration.catalog.infrastructure.config;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import ar.edu.um.prog2.appointmentreservation.integration.catalog.infrastructure.security.CatalogInternalTokenIssuer;

@Configuration(proxyBeanMethods = false)
public class CatalogIntegrationConfiguration {

	@Bean
	public CatalogInternalTokenIssuer catalogInternalTokenIssuer(CatalogIntegrationProperties properties) {
		return new CatalogInternalTokenIssuer(properties, Clock.systemUTC());
	}
}
