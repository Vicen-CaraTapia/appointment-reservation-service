package ar.edu.um.prog2.appointmentreservation.availability.application.usecase;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import ar.edu.um.prog2.appointmentreservation.availability.application.exception.OccupancyLookupException;
import ar.edu.um.prog2.appointmentreservation.availability.application.exception.InvalidAvailabilityScheduleException;
import ar.edu.um.prog2.appointmentreservation.availability.application.port.out.CurrentOccupancySource;
import ar.edu.um.prog2.appointmentreservation.availability.application.service.AvailableAppointmentSlotsCalculator;
import ar.edu.um.prog2.appointmentreservation.professional.application.exception.ProfessionalLookupException;
import ar.edu.um.prog2.appointmentreservation.professional.application.model.CurrentProfessional;
import ar.edu.um.prog2.appointmentreservation.professional.application.port.out.CurrentProfessionalSource;
import ar.edu.um.prog2.appointmentreservation.professional.application.usecase.LoadEnabledProfessionalUseCase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CalculateAppointmentAvailabilityUseCaseTest {

	private static final LocalDate DATE = LocalDate.of(2026, 9, 14);
	@Mock
	private CurrentProfessionalSource professionals;
	@Mock
	private CurrentOccupancySource occupancies;
	private CalculateAppointmentAvailabilityUseCase useCase;

	@BeforeEach
	void createUseCaseWithRealProfessionalValidation() {
		useCase = new CalculateAppointmentAvailabilityUseCase(new LoadEnabledProfessionalUseCase(professionals),
				occupancies, new AvailableAppointmentSlotsCalculator());
	}

	@Test
	void combinesCurrentSchedulesAndOccupanciesAfterValidatingTheProfessional() {
		when(professionals.load(201)).thenReturn(professional(true));
		when(occupancies.loadBlockedStartTimes(201, DATE)).thenReturn(Set.of(LocalTime.of(9, 0), LocalTime.of(10, 30)));
		assertThat(useCase.calculate(201, DATE)).containsExactly(
				LocalTime.of(9, 30), LocalTime.of(10, 0), LocalTime.of(11, 0), LocalTime.of(11, 30));
		var order = inOrder(professionals, occupancies);
		order.verify(professionals).load(201);
		order.verify(occupancies).loadBlockedStartTimes(201, DATE);
		verifyNoMoreInteractions(professionals, occupancies);
	}

	@Test
	void anEmptyOccupancyResponseLeavesEveryGeneratedSlotAvailable() {
		when(professionals.load(201)).thenReturn(professional(true));
		when(occupancies.loadBlockedStartTimes(201, DATE)).thenReturn(Set.of());
		assertThat(useCase.calculate(201, DATE)).containsExactly(LocalTime.of(9, 0), LocalTime.of(9, 30),
				LocalTime.of(10, 0), LocalTime.of(10, 30), LocalTime.of(11, 0), LocalTime.of(11, 30));
	}

	@Test
	void rejectsAMissingDateBeforeConsultingEitherSource() {
		assertThatThrownBy(() -> useCase.calculate(201, null)).isInstanceOf(IllegalArgumentException.class)
				.hasMessage("Availability date is required");
		verifyNoInteractions(professionals, occupancies);
	}

	@Test
	void rejectsADisabledProfessionalBeforeConsultingOccupancies() {
		when(professionals.load(201)).thenReturn(professional(false));
		assertThatThrownBy(() -> useCase.calculate(201, DATE)).isInstanceOfSatisfying(ProfessionalLookupException.class,
				failure -> assertThat(failure.reason()).isEqualTo(ProfessionalLookupException.Reason.DISABLED));
		verifyNoInteractions(occupancies);
	}

	@ParameterizedTest
	@EnumSource(ProfessionalLookupException.Reason.class)
	void catalogFailuresArePropagatedWithoutConsultingOccupanciesOrInventingSlots(ProfessionalLookupException.Reason reason) {
		var failure = new ProfessionalLookupException(reason);
		when(professionals.load(201)).thenThrow(failure);
		assertThatThrownBy(() -> useCase.calculate(201, DATE)).isSameAs(failure);
		verify(professionals).load(201);
		verifyNoInteractions(occupancies);
		verifyNoMoreInteractions(professionals);
	}

	@ParameterizedTest
	@EnumSource(OccupancyLookupException.Reason.class)
	void occupancyFailuresArePropagatedRatherThanTreatingUnverifiedSlotsAsFree(OccupancyLookupException.Reason reason) {
		when(professionals.load(201)).thenReturn(professional(true));
		var failure = new OccupancyLookupException(reason);
		when(occupancies.loadBlockedStartTimes(201, DATE)).thenThrow(failure);
		assertThatThrownBy(() -> useCase.calculate(201, DATE)).isSameAs(failure);
		verify(professionals).load(201);
		verify(occupancies).loadBlockedStartTimes(201, DATE);
		verifyNoMoreInteractions(professionals, occupancies);
	}

	@ParameterizedTest
	@ValueSource(ints = {0, 1, 2})
	void noSchedulesDisabledSchedulesOrAnotherWeekdayReturnEmptyWithoutAnOccupancyQuery(int scenario) {
		var schedules = switch (scenario) {
			case 0 -> List.<CurrentProfessional.WeeklySchedule>of();
			case 1 -> List.of(schedule(DayOfWeek.MONDAY, false));
			default -> List.of(schedule(DayOfWeek.TUESDAY, true));
		};
		when(professionals.load(201)).thenReturn(professional(schedules));
		assertThat(useCase.calculate(201, DATE)).isEmpty();
		verify(professionals).load(201);
		verifyNoInteractions(occupancies);
	}

	@Test
	void doesNotReusePreviouslySuccessfulOccupanciesAfterAFailure() {
		when(professionals.load(201)).thenReturn(professional(true));
		var failure = new OccupancyLookupException(OccupancyLookupException.Reason.UNAVAILABLE);
		when(occupancies.loadBlockedStartTimes(201, DATE)).thenReturn(Set.of()).thenThrow(failure);
		assertThat(useCase.calculate(201, DATE)).hasSize(6);
		assertThatThrownBy(() -> useCase.calculate(201, DATE)).isSameAs(failure);
		verify(professionals, times(2)).load(201);
		verify(occupancies, times(2)).loadBlockedStartTimes(201, DATE);
	}

	@Test
	void consultsBothSourcesAgainAndSeesNewOccupancies() {
		when(professionals.load(201)).thenReturn(professional(true));
		when(occupancies.loadBlockedStartTimes(201, DATE)).thenReturn(Set.of()).thenReturn(Set.of(LocalTime.of(9, 0)));
		assertThat(useCase.calculate(201, DATE)).hasSize(6);
		assertThat(useCase.calculate(201, DATE)).hasSize(5).doesNotContain(LocalTime.of(9, 0));
		verify(professionals, times(2)).load(201);
		verify(occupancies, times(2)).loadBlockedStartTimes(201, DATE);
	}

	@Test
	void aProfessionalDisabledSinceThePreviousQueryDoesNotReuseOldSchedules() {
		when(professionals.load(201)).thenReturn(professional(true), professional(false));
		when(occupancies.loadBlockedStartTimes(201, DATE)).thenReturn(Set.of());
		assertThat(useCase.calculate(201, DATE)).hasSize(6);
		assertThatThrownBy(() -> useCase.calculate(201, DATE)).isInstanceOf(ProfessionalLookupException.class);
		verify(professionals, times(2)).load(201);
		verify(occupancies).loadBlockedStartTimes(201, DATE);
	}

	@Test
	void missingOccupancyDataCannotBeInterpretedAsAnEmptyResponse() {
		when(professionals.load(201)).thenReturn(professional(true));
		when(occupancies.loadBlockedStartTimes(201, DATE)).thenReturn(null);
		assertThatThrownBy(() -> useCase.calculate(201, DATE)).isInstanceOf(NullPointerException.class);
	}

	@Test
	void anInvalidApplicableScheduleDoesNotProducePartialOrEmptySuccess() {
		when(professionals.load(201)).thenReturn(professional(List.of(
				new CurrentProfessional.WeeklySchedule(301, DayOfWeek.MONDAY, LocalTime.of(9, 0), LocalTime.NOON, 0, true))));
		when(occupancies.loadBlockedStartTimes(201, DATE)).thenReturn(Set.of());
		assertThatThrownBy(() -> useCase.calculate(201, DATE)).isInstanceOf(InvalidAvailabilityScheduleException.class);
	}

	private static CurrentProfessional professional(boolean enabled) {
		return new CurrentProfessional(201, "Ana", "Torres", enabled,
				new CurrentProfessional.Category(101, "Cardiologia", true), List.of(schedule(DayOfWeek.MONDAY, true)));
	}

	private static CurrentProfessional professional(List<CurrentProfessional.WeeklySchedule> schedules) {
		return new CurrentProfessional(201, "Ana", "Torres", true,
				new CurrentProfessional.Category(101, "Cardiologia", true), schedules);
	}

	private static CurrentProfessional.WeeklySchedule schedule(DayOfWeek day, boolean enabled) {
		return new CurrentProfessional.WeeklySchedule(301, day, LocalTime.of(9, 0), LocalTime.NOON, 30, enabled);
	}
}
