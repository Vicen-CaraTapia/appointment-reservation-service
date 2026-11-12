package ar.edu.um.prog2.appointmentreservation.integration.catalog.infrastructure.rest.adapter;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import ar.edu.um.prog2.appointmentreservation.integration.catalog.infrastructure.rest.client.CatalogProfessionalClient;
import ar.edu.um.prog2.appointmentreservation.integration.catalog.infrastructure.rest.dto.CatalogProfessionalResponse;
import ar.edu.um.prog2.appointmentreservation.integration.catalog.infrastructure.rest.exception.CatalogClientException;
import ar.edu.um.prog2.appointmentreservation.professional.application.exception.ProfessionalLookupException;
import ar.edu.um.prog2.appointmentreservation.professional.application.exception.ProfessionalLookupException.Reason;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CatalogCurrentProfessionalAdapterTest {

	@Mock
	private CatalogProfessionalClient client;

	@Test
	void mapsTheTransportDtoIntoAnImmutableApplicationViewWithoutLosingData() {
		var time = LocalTime.of(9, 0, 0, 123456789);
		var dto = new CatalogProfessionalResponse(Long.MAX_VALUE, "Ana", "Torres", true,
				new CatalogProfessionalResponse.Category(101, "Cardiologia", false), List.of(
						new CatalogProfessionalResponse.WeeklySchedule(301, DayOfWeek.TUESDAY, time, LocalTime.NOON, 30, false)));
		when(client.getProfessional(Long.MAX_VALUE)).thenReturn(dto);
		var professional = new CatalogCurrentProfessionalAdapter(client).load(Long.MAX_VALUE);
		assertThat(professional.id()).isEqualTo(Long.MAX_VALUE);
		assertThat(professional.firstName()).isEqualTo("Ana");
		assertThat(professional.lastName()).isEqualTo("Torres");
		assertThat(professional.enabled()).isTrue();
		assertThat(professional.category().id()).isEqualTo(101);
		assertThat(professional.category().name()).isEqualTo("Cardiologia");
		assertThat(professional.category().enabled()).isFalse();
		var schedule = professional.weeklySchedules().getFirst();
		assertThat(schedule.id()).isEqualTo(301);
		assertThat(schedule.dayOfWeek()).isEqualTo(DayOfWeek.TUESDAY);
		assertThat(schedule.startTime()).isEqualTo(time);
		assertThat(schedule.endTime()).isEqualTo(LocalTime.NOON);
		assertThat(schedule.slotDurationMinutes()).isEqualTo(30);
		assertThat(schedule.enabled()).isFalse();
		assertThatThrownBy(() -> professional.weeklySchedules().clear()).isInstanceOf(UnsupportedOperationException.class);
		verify(client).getProfessional(Long.MAX_VALUE);
		verifyNoMoreInteractions(client);
	}

	@Test
	void keepsDisabledAndEmptyResultsForTheApplicationToEvaluate() {
		when(client.getProfessional(201)).thenReturn(new CatalogProfessionalResponse(201, "Ana", "Torres", false,
				new CatalogProfessionalResponse.Category(101, "Cardiologia", true), List.of()));
		var professional = new CatalogCurrentProfessionalAdapter(client).load(201);
		assertThat(professional.enabled()).isFalse();
		assertThat(professional.weeklySchedules()).isEmpty();
	}

	@ParameterizedTest(name = "application failure mapping {index}")
	@MethodSource("failures")
	void translatesTechnicalFailuresWithoutLeakingHttpExceptions(CatalogClientException technical, Reason reason) {
		when(client.getProfessional(201)).thenThrow(technical);
		assertThatThrownBy(() -> new CatalogCurrentProfessionalAdapter(client).load(201))
				.isInstanceOfSatisfying(ProfessionalLookupException.class, failure -> {
					assertThat(failure.reason()).isEqualTo(reason);
					assertThat(failure).hasMessage("Current professional lookup failed: " + reason).hasNoCause();
				});
		verify(client).getProfessional(201);
		verifyNoMoreInteractions(client);
	}

	static Stream<Arguments> failures() {
		return Stream.of(
				Arguments.of(CatalogClientException.timeout(), Reason.CATALOG_TIMEOUT),
				Arguments.of(CatalogClientException.transport(), Reason.CATALOG_UNAVAILABLE),
				Arguments.of(CatalogClientException.invalidResponse(), Reason.INVALID_CATALOG_RESPONSE),
				Arguments.of(CatalogClientException.http(401), Reason.CATALOG_UNAUTHORIZED),
				Arguments.of(CatalogClientException.http(403), Reason.CATALOG_FORBIDDEN),
				Arguments.of(CatalogClientException.http(404), Reason.NOT_FOUND),
				Arguments.of(CatalogClientException.http(500), Reason.CATALOG_UNAVAILABLE),
				Arguments.of(CatalogClientException.http(503), Reason.CATALOG_UNAVAILABLE),
				Arguments.of(CatalogClientException.http(599), Reason.CATALOG_UNAVAILABLE),
				Arguments.of(CatalogClientException.http(400), Reason.INVALID_CATALOG_RESPONSE),
				Arguments.of(CatalogClientException.http(302), Reason.INVALID_CATALOG_RESPONSE),
				Arguments.of(CatalogClientException.http(409), Reason.INVALID_CATALOG_RESPONSE),
				Arguments.of(CatalogClientException.http(600), Reason.INVALID_CATALOG_RESPONSE));
	}
}
