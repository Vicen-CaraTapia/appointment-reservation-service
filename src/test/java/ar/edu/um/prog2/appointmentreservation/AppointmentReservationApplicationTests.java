package ar.edu.um.prog2.appointmentreservation;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.client.RestTestClient;

import ar.edu.um.prog2.appointmentreservation.user.application.port.out.UserRegistrationStore;
import ar.edu.um.prog2.appointmentreservation.user.infrastructure.persistence.repository.UserJpaRepository;
import ar.edu.um.prog2.appointmentreservation.support.FinalUserJwtTestSupport;

@SpringBootTest(
		webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		useMainMethod = SpringBootTest.UseMainMethod.ALWAYS
)
@AutoConfigureRestTestClient
@ActiveProfiles("test")
class AppointmentReservationApplicationTests {

	@MockitoBean
	private UserRegistrationStore userRegistrationStore;

	@MockitoBean
	private UserJpaRepository users;

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

}
