package ar.edu.um.prog2.appointmentreservation;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
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
import ar.edu.um.prog2.appointmentreservation.integration.catalog.infrastructure.rest.dto.CatalogProfessionalResponse;
import ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.rest.client.CatedraAppointmentOccupanciesClient;
import ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.rest.dto.CatedraAppointmentOccupanciesResponse;
import ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.rest.dto.CatedraAppointmentOccupanciesResponse.Occupancy;
import ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.rest.dto.CatedraAppointmentOccupanciesResponse.Status;
import ar.edu.um.prog2.appointmentreservation.availability.application.usecase.CalculateAppointmentAvailabilityUseCase;
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
import ar.edu.um.prog2.appointmentreservation.reservation.application.port.out.ReservationProcessStore;
import ar.edu.um.prog2.appointmentreservation.reservation.application.port.out.ReservationEventStore;
import ar.edu.um.prog2.appointmentreservation.reservation.application.port.out.PhoneSubmissionStore;

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
	private PhoneSubmissionStore phoneSubmissionStore;

	@MockitoBean
	private ReservationEventStore reservationEventStore;

	@MockitoBean
	private ReservationProcessStore reservationProcessStore;

	@MockitoBean
	private UserRegistrationStore userRegistrationStore;

	@MockitoBean
	private UserJpaRepository users;

	@MockitoBean
	private CatalogProfessionalClient catalogClient;

	@MockitoBean
	private CatedraAppointmentOccupanciesClient occupancyClient;

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
	void availabilityUsesTheWiredApplicationAndAdaptersWithoutExternalInfrastructure(
			@Autowired CalculateAppointmentAvailabilityUseCase availability) {
		var date = LocalDate.of(2026, 9, 14);
		when(catalogClient.getProfessional(201)).thenReturn(new CatalogProfessionalResponse(201, "Ana", "Torres", true,
				new CatalogProfessionalResponse.Category(101, "Cardiologia", true), List.of(
						new CatalogProfessionalResponse.WeeklySchedule(301, DayOfWeek.MONDAY,
								LocalTime.of(9, 0), LocalTime.NOON, 30, true))));
		when(occupancyClient.getOccupancies(201, date, date)).thenReturn(
				new CatedraAppointmentOccupanciesResponse(201, date, date, List.of(
						new Occupancy(date, LocalTime.of(9, 0), Status.CONFIRMED, null),
						new Occupancy(date, LocalTime.of(10, 30), Status.HELD, Instant.EPOCH))));
		assertThat(availability.calculate(201, date)).containsExactly(
				LocalTime.of(9, 30), LocalTime.of(10, 0), LocalTime.of(11, 0), LocalTime.of(11, 30));
		verify(catalogClient).getProfessional(201);
		verify(occupancyClient).getOccupancies(201, date, date);
		verifyNoMoreInteractions(catalogClient, occupancyClient);
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
