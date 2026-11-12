package ar.edu.um.prog2.appointmentreservation;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.springframework.test.util.ReflectionTestUtils;

import ar.edu.um.prog2.appointmentreservation.integration.catalog.infrastructure.rest.client.CatalogProfessionalClient;
import ar.edu.um.prog2.appointmentreservation.integration.catalog.infrastructure.rest.exception.CatalogClientException;
import ar.edu.um.prog2.appointmentreservation.professional.application.exception.ProfessionalLookupException;
import ar.edu.um.prog2.appointmentreservation.professional.application.usecase.LoadEnabledProfessionalUseCase;
import ar.edu.um.prog2.appointmentreservation.user.application.model.RegisteredUser;
import ar.edu.um.prog2.appointmentreservation.user.application.port.out.UserRegistrationStore;
import ar.edu.um.prog2.appointmentreservation.user.infrastructure.persistence.entity.AuthorityEntity;
import ar.edu.um.prog2.appointmentreservation.user.infrastructure.persistence.entity.UserEntity;
import ar.edu.um.prog2.appointmentreservation.user.infrastructure.persistence.repository.UserJpaRepository;
import ar.edu.um.prog2.appointmentreservation.support.FinalUserJwtTestSupport;
import ar.edu.um.prog2.appointmentreservation.support.CatalogIntegrationTestSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@SpringBootTest(
		webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		useMainMethod = SpringBootTest.UseMainMethod.ALWAYS
)
@AutoConfigureRestTestClient
@ActiveProfiles("test")
@ContextConfiguration(initializers = CatalogIntegrationTestSupport.class)
class AppointmentReservationApplicationTests {

	@MockitoBean
	private UserRegistrationStore userRegistrationStore;

	@MockitoBean
	private UserJpaRepository users;

	@MockitoBean
	private CatalogProfessionalClient catalogClient;

	@DynamicPropertySource
	static void signingKey(DynamicPropertyRegistry registry) {
		registry.add(FinalUserJwtTestSupport.SECRET_PROPERTY, FinalUserJwtTestSupport::signingSecret);
	}

	@Test
	void healthIsUp(@Autowired RestTestClient restClient) {
		restClient.get()
				.uri("/actuator/health")
				.exchange()
				.expectStatus().isOk()
				.expectBody()
				.jsonPath("$.status").isEqualTo("UP");
	}

	@Test
	void localRegistrationAndAuthenticationRemainAvailableWhenCatalogLookupFails(
			@Autowired RestTestClient restClient, @Autowired LoadEnabledProfessionalUseCase lookup,
			@Autowired PasswordEncoder passwords) {
		when(catalogClient.getProfessional(201)).thenThrow(CatalogClientException.transport());
		assertThatThrownBy(() -> lookup.load(201)).isInstanceOfSatisfying(ProfessionalLookupException.class,
				failure -> assertThat(failure.reason()).isEqualTo(ProfessionalLookupException.Reason.CATALOG_UNAVAILABLE));

		String password = "local-only test passphrase";
		when(userRegistrationStore.create(any())).thenReturn(new RegisteredUser(42L, "localuser", null, null,
				"local@example.test", null, "es", true, Set.of("ROLE_USER")));
		restClient.post().uri("/api/register").contentType(MediaType.APPLICATION_JSON)
				.body(Map.of("login", "localuser", "password", password, "email", "local@example.test"))
				.exchange().expectStatus().isCreated().expectBody().jsonPath("$.login").isEqualTo("localuser");

		var user = new UserEntity("localuser", passwords.encode(password), null, null,
				"local@example.test", null, "es", "localuser");
		user.activate("localuser");
		user.grantAuthority(new AuthorityEntity("ROLE_USER"), "localuser");
		ReflectionTestUtils.setField(user, "id", 42L);
		when(users.findByLoginIgnoreCase("localuser")).thenReturn(Optional.of(user));
		restClient.post().uri("/api/authenticate").contentType(MediaType.APPLICATION_JSON)
				.body(Map.of("username", "localuser", "password", password))
				.exchange().expectStatus().isOk().expectBody().jsonPath("$.id_token").isNotEmpty();

		verify(catalogClient).getProfessional(201);
		verifyNoMoreInteractions(catalogClient);
	}

}
