package ar.edu.um.prog2.appointmentreservation.professional.application.usecase;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import ar.edu.um.prog2.appointmentreservation.professional.application.exception.ProfessionalLookupException;
import ar.edu.um.prog2.appointmentreservation.professional.application.exception.ProfessionalLookupException.Reason;
import ar.edu.um.prog2.appointmentreservation.professional.application.model.CurrentProfessional;
import ar.edu.um.prog2.appointmentreservation.professional.application.port.out.CurrentProfessionalSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LoadEnabledProfessionalUseCaseTest {

	@Mock
	private CurrentProfessionalSource source;

	@Test
	void returnsTheCurrentEnabledProfessionalAndPreservesItsScheduleRules() {
		var professional = professional(true);
		when(source.load(201)).thenReturn(professional);
		assertThat(new LoadEnabledProfessionalUseCase(source).load(201)).isSameAs(professional);
		verify(source).load(201);
		verifyNoMoreInteractions(source);
	}

	@Test
	void disabledProfessionalCannotPassTheApplicationBoundary() {
		when(source.load(201)).thenReturn(professional(false));
		assertThatThrownBy(() -> new LoadEnabledProfessionalUseCase(source).load(201))
				.isInstanceOfSatisfying(ProfessionalLookupException.class, failure -> {
					assertThat(failure.reason()).isEqualTo(Reason.DISABLED);
					assertThat(failure).hasNoCause();
				});
		verify(source).load(201);
		verifyNoMoreInteractions(source);
	}

	@ParameterizedTest
	@EnumSource(value = Reason.class, names = "DISABLED", mode = EnumSource.Mode.EXCLUDE)
	void lookupFailuresStopTheLookupWithoutFallbackOrRetries(Reason reason) {
		var failure = new ProfessionalLookupException(reason);
		when(source.load(201)).thenThrow(failure);
		assertThatThrownBy(() -> new LoadEnabledProfessionalUseCase(source).load(201)).isSameAs(failure);
		verify(source).load(201);
		verifyNoMoreInteractions(source);
	}

	@Test
	void doesNotReuseAPreviousResultWhenTheNextLookupFails() {
		var professional = professional(true);
		var failure = new ProfessionalLookupException(Reason.CATALOG_UNAVAILABLE);
		when(source.load(201)).thenReturn(professional).thenThrow(failure);
		var useCase = new LoadEnabledProfessionalUseCase(source);
		assertThat(useCase.load(201)).isSameAs(professional);
		assertThatThrownBy(() -> useCase.load(201)).isSameAs(failure);
		verify(source, times(2)).load(201);
		verifyNoMoreInteractions(source);
	}

	@Test
	void consultsAgainAndRejectsAProfessionalDisabledSinceThePreviousLookup() {
		when(source.load(201)).thenReturn(professional(true), professional(false));
		var useCase = new LoadEnabledProfessionalUseCase(source);
		assertThat(useCase.load(201).enabled()).isTrue();
		assertThatThrownBy(() -> useCase.load(201)).isInstanceOfSatisfying(ProfessionalLookupException.class,
				failure -> assertThat(failure.reason()).isEqualTo(Reason.DISABLED));
		verify(source, times(2)).load(201);
	}

	@Test
	void anEmptyScheduleListRemainsAValidLookupRatherThanInventedAvailability() {
		var professional = new CurrentProfessional(201, "Ana", "Torres", true,
				new CurrentProfessional.Category(101, "Cardiologia", true), List.of());
		when(source.load(201)).thenReturn(professional);
		assertThat(new LoadEnabledProfessionalUseCase(source).load(201).weeklySchedules()).isEmpty();
	}

	private static CurrentProfessional professional(boolean enabled) {
		return new CurrentProfessional(201, "Ana", "Torres", enabled,
				new CurrentProfessional.Category(101, "Cardiologia", true), List.of(
						new CurrentProfessional.WeeklySchedule(301, DayOfWeek.MONDAY, LocalTime.of(9, 0), LocalTime.of(12, 0), 30, true),
						new CurrentProfessional.WeeklySchedule(302, DayOfWeek.TUESDAY, LocalTime.of(9, 0), LocalTime.of(12, 0), 30, false)));
	}
}
