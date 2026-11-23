package ar.edu.um.prog2.appointmentreservation.reservation.infrastructure.web.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import ar.edu.um.prog2.appointmentreservation.reservation.application.model.OwnedReservationPage;
import ar.edu.um.prog2.appointmentreservation.reservation.application.model.TechnicalReservation;
import ar.edu.um.prog2.appointmentreservation.reservation.domain.model.ReservationProcessStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OwnedReservationsResponseTest {
	@Test
	void mapsLocalIdentitySeparatelyAndRedactsPersonalDataInStringRepresentations() {
		var now = Instant.parse("2030-01-01T12:00:00Z");
		var external = new TechnicalReservation(8251, "private-process", "user-42",
				LocalDate.of(2030, 1, 10), LocalTime.of(9, 30), LocalTime.of(10, 0),
				201, "SyntheticFirst", "SyntheticLast", "PrivatePatient", "PrivateLast",
				"+5492615551234", TechnicalReservation.Status.CANCELLED, now, now, now, "private-reason");
		var response = OwnedReservationsResponse.from(new OwnedReservationPage(
				List.of(new OwnedReservationPage.Item(12, external, ReservationProcessStatus.CANCELLED, now)), 1, 0, 20));
		assertThat(response.total()).isEqualTo(1);
		assertThat(response.page()).isZero();
		assertThat(response.size()).isEqualTo(20);
		var item = response.items().getFirst();
		assertThat(item.id()).isEqualTo(12);
		assertThat(item.professionalId()).isEqualTo(201);
		assertThat(item.professionalFirstName()).isEqualTo("SyntheticFirst");
		assertThat(item.professionalLastName()).isEqualTo("SyntheticLast");
		assertThat(item.appointmentDate()).isEqualTo(external.appointmentDate());
		assertThat(item.startTime()).isEqualTo(external.startTime());
		assertThat(item.endTime()).isEqualTo(external.endTime());
		assertThat(item.processStatus()).isEqualTo(ReservationProcessStatus.CANCELLED);
		assertThat(item.reservationStatus()).isEqualTo(TechnicalReservation.Status.CANCELLED);
		assertThat(item.createdAt()).isEqualTo(now);
		assertThat(item.confirmedAt()).isEqualTo(now);
		assertThat(item.cancelledAt()).isEqualTo(now);
		assertThat(response.toString()).isEqualTo("OwnedReservationsResponse[redacted]");
		assertThat(item.toString()).isEqualTo("OwnedReservation[redacted]");
	}

	@Test
	void responseRetainsAnImmutableSnapshotOfItsItems() {
		var items = new ArrayList<OwnedReservationsResponse.Reservation>();
		var response = new OwnedReservationsResponse(items, 0, 0, 20);
		items.add(null);
		assertThat(response.items()).isEmpty();
		assertThatThrownBy(() -> response.items().add(null)).isInstanceOf(UnsupportedOperationException.class);
	}
}
