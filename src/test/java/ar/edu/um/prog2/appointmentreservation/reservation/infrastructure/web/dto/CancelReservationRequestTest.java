package ar.edu.um.prog2.appointmentreservation.reservation.infrastructure.web.dto;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CancelReservationRequestTest {

	private final JsonMapper json = new JsonMapper();

	@Test
	void acceptsNullableStringAndNeverSerializesTheReason() throws Exception {
		assertThat(json.readValue("{}", CancelReservationRequest.class).reason()).isNull();
		assertThat(json.readValue("{\"reason\":null}", CancelReservationRequest.class).reason()).isNull();
		var request = json.readValue("{\"reason\":\"synthetic reason\"}", CancelReservationRequest.class);
		assertThat(request.toString()).doesNotContain("synthetic reason");
		assertThat(json.writeValueAsString(request)).doesNotContain("reason", "synthetic reason");
	}

	@Test
	void rejectsNonStringReasonWithoutCoercion() {
		assertThatThrownBy(() -> json.readValue("{\"reason\":123}", CancelReservationRequest.class))
				.isInstanceOf(RuntimeException.class)
				.hasMessageContaining("Cancellation reason must be a JSON string");
	}

	@Test
	void rejectsUnknownProperties() {
		assertThatThrownBy(() -> json.readValue("{\"ownerId\":42}", CancelReservationRequest.class))
				.isInstanceOf(RuntimeException.class)
				.hasMessageContaining("Unsupported cancellation property");
	}
}
