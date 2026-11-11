package ar.edu.um.prog2.appointmentreservation.integration.catalog.infrastructure.rest.client;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import ar.edu.um.prog2.appointmentreservation.integration.catalog.infrastructure.config.CatalogIntegrationConfiguration;
import ar.edu.um.prog2.appointmentreservation.integration.catalog.infrastructure.config.CatalogIntegrationProperties;
import ar.edu.um.prog2.appointmentreservation.integration.catalog.infrastructure.rest.exception.CatalogClientException;
import ar.edu.um.prog2.appointmentreservation.integration.catalog.infrastructure.rest.exception.CatalogClientException.Reason;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Explicit, read-only checks against a running Catalog instance with externally supplied keys. */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = CatalogProfessionalClientIT.ClientConfiguration.class,
		initializers = ConfigDataApplicationContextInitializer.class)
class CatalogProfessionalClientIT {

	@Autowired
	private CatalogProfessionalClient client;

	@Autowired
	private Environment environment;

	@Test
	void readsAProfessionalThroughTheRealInternalContract() {
		long existingId = requiredId("APPOINTMENT_TEST_CATALOG_PROFESSIONAL_ID");
		var professional = client.getProfessional(existingId);
		assertThat(professional.id()).isEqualTo(existingId);
		assertThat(professional.firstName()).isNotNull();
		assertThat(professional.lastName()).isNotNull();
		assertThat(professional.category()).isNotNull();
		assertThat(professional.weeklySchedules()).isNotNull();
		System.out.printf("Catalog integration OK: professionalId=%d, enabled=%s, categoryId=%d, weeklySchedules=%d%n",
				professional.id(), professional.enabled(), professional.category().id(), professional.weeklySchedules().size());
	}

	@Test
	void preservesNotFoundForAnExplicitlyUnknownProfessional() {
		long missingId = requiredId("APPOINTMENT_TEST_CATALOG_MISSING_PROFESSIONAL_ID");
		assertThatThrownBy(() -> client.getProfessional(missingId))
				.isInstanceOfSatisfying(CatalogClientException.class, error -> {
					assertThat(error.reason()).isEqualTo(Reason.HTTP);
					assertThat(error.status()).hasValue(404);
					assertThat(error).hasNoCause();
				});
		System.out.println("Catalog integration OK: unknown professional returns HTTP 404");
	}

	private long requiredId(String name) {
		String value = environment.getProperty(name);
		try {
			return Long.parseLong(value == null ? "" : value);
		} catch (NumberFormatException exception) {
			throw new IllegalStateException("Set " + name + " to a valid signed 64-bit professional ID for catalog-integration");
		}
	}

	@TestConfiguration(proxyBeanMethods = false)
	@EnableConfigurationProperties(CatalogIntegrationProperties.class)
	@Import(CatalogIntegrationConfiguration.class)
	static class ClientConfiguration {
		@Bean
		static LocalValidatorFactoryBean configurationPropertiesValidator() {
			return new LocalValidatorFactoryBean();
		}
	}
}
