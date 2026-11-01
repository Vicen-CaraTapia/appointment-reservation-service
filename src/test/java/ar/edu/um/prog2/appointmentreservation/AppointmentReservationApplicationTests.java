package ar.edu.um.prog2.appointmentreservation;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.client.RestTestClient;

@SpringBootTest(
		webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		useMainMethod = SpringBootTest.UseMainMethod.ALWAYS
)
@AutoConfigureRestTestClient
@ActiveProfiles("test")
class AppointmentReservationApplicationTests {

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
