package ar.edu.um.prog2.appointmentreservation.reservation.application.service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import ar.edu.um.prog2.appointmentreservation.availability.application.exception.InvalidAvailabilityScheduleException;
import ar.edu.um.prog2.appointmentreservation.availability.application.service.AvailableAppointmentSlotsCalculator;
import ar.edu.um.prog2.appointmentreservation.professional.application.exception.ProfessionalLookupException;
import ar.edu.um.prog2.appointmentreservation.professional.application.model.CurrentProfessional;
import ar.edu.um.prog2.appointmentreservation.professional.application.model.CurrentProfessional.WeeklySchedule;
import ar.edu.um.prog2.appointmentreservation.professional.application.port.out.CurrentProfessionalSource;
import ar.edu.um.prog2.appointmentreservation.professional.application.usecase.LoadEnabledProfessionalUseCase;
import ar.edu.um.prog2.appointmentreservation.reservation.application.exception.InvalidReservationSlotException;
import ar.edu.um.prog2.appointmentreservation.reservation.application.model.StartReservationCommand;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReservationSlotValidatorTest {

	private static final LocalDate DATE = LocalDate.of(2026, 11, 19);
	@Mock
	private CurrentProfessionalSource source;
	private ReservationSlotValidator validator;

	@BeforeEach
	void usesRealProfessionalValidationAndTheExistingSlotCalculator() {
		validator = new ReservationSlotValidator(new LoadEnabledProfessionalUseCase(source),
				new AvailableAppointmentSlotsCalculator());
	}

	@ParameterizedTest
	@ValueSource(strings = {"18:00", "18:30", "19:00", "19:30", "20:00", "20:30"})
	void acceptsOnlyCompleteSlotsFromTheCurrentEnabledSchedule(String time) {
		when(source.load(202)).thenReturn(professional(true, List.of(schedule(true, 30))));
		assertThatCode(() -> validator.validate(selection(time))).doesNotThrowAnyException();
		verify(source).load(202);
		verifyNoMoreInteractions(source);
	}

	@ParameterizedTest
	@ValueSource(strings = {"17:30", "18:01", "18:15", "18:00:01", "18:00:00.000001", "20:45", "21:00", "23:59"})
	void rejectsOutOfRangeMisalignedOrNonExactSlotStarts(String time) {
		when(source.load(202)).thenReturn(professional(true, List.of(schedule(true, 30))));
		assertThatThrownBy(() -> validator.validate(selection(time)))
				.isInstanceOf(InvalidReservationSlotException.class).hasNoCause();
	}

	@Test
	void rejectsATailThatCannotFitACompleteSlot() {
		when(source.load(202)).thenReturn(professional(true, List.of(new WeeklySchedule(304,
				DayOfWeek.THURSDAY, LocalTime.of(18, 0), LocalTime.of(19, 15), 30, true))));
		assertThatCode(() -> validator.validate(selection("18:30"))).doesNotThrowAnyException();
		assertThatThrownBy(() -> validator.validate(selection("19:00"))).isInstanceOf(InvalidReservationSlotException.class);
	}

	@Test
	void rejectsNoScheduleDisabledScheduleAndAnotherWeekday() {
		for (var schedules : List.of(List.<WeeklySchedule>of(), List.of(schedule(false, 30)),
				List.of(new WeeklySchedule(303, DayOfWeek.TUESDAY, LocalTime.of(18, 0), LocalTime.of(21, 0), 30, true)))) {
			when(source.load(202)).thenReturn(professional(true, schedules));
			assertThatThrownBy(() -> validator.validate(selection("18:00"))).isInstanceOf(InvalidReservationSlotException.class);
		}
	}

	@Test
	void rejectsADisabledProfessionalEvenWhenItsScheduleContainsTheSlot() {
		when(source.load(202)).thenReturn(professional(false, List.of(schedule(true, 30))));
		assertThatThrownBy(() -> validator.validate(selection("18:00")))
				.isInstanceOfSatisfying(ProfessionalLookupException.class,
						failure -> assertThat(failure.reason()).isEqualTo(ProfessionalLookupException.Reason.DISABLED));
	}

	@ParameterizedTest
	@EnumSource(ProfessionalLookupException.Reason.class)
	void propagatesMissingProfessionalsAndCatalogFailuresWithoutInventingAValidSlot(ProfessionalLookupException.Reason reason) {
		var failure = new ProfessionalLookupException(reason);
		when(source.load(202)).thenThrow(failure);
		assertThatThrownBy(() -> validator.validate(selection("18:00"))).isSameAs(failure);
		verify(source).load(202);
		verifyNoMoreInteractions(source);
	}

	@Test
	void queriesCatalogAgainAndRejectsAScheduleDisabledSinceThePreviousSelection() {
		when(source.load(202)).thenReturn(professional(true, List.of(schedule(true, 30))),
				professional(true, List.of(schedule(false, 30))));
		assertThatCode(() -> validator.validate(selection("18:00"))).doesNotThrowAnyException();
		assertThatThrownBy(() -> validator.validate(selection("18:00"))).isInstanceOf(InvalidReservationSlotException.class);
		verify(source, times(2)).load(202);
	}

	@Test
	void malformedApplicableSchedulesKeepTheirExistingErrorClassification() {
		when(source.load(202)).thenReturn(professional(true, List.of(schedule(true, 0))));
		assertThatThrownBy(() -> validator.validate(selection("18:00"))).isInstanceOf(InvalidAvailabilityScheduleException.class);
	}

	@Test
	void rejectsMissingSelectionBeforeConsultingCatalog() {
		assertThatThrownBy(() -> validator.validate(null)).isInstanceOf(NullPointerException.class);
		verifyNoInteractions(source);
	}

	private static StartReservationCommand selection(String time) {
		return new StartReservationCommand(202, DATE, LocalTime.parse(time));
	}

	private static WeeklySchedule schedule(boolean enabled, int duration) {
		return new WeeklySchedule(304, DayOfWeek.THURSDAY, LocalTime.of(18, 0), LocalTime.of(21, 0), duration, enabled);
	}

	private static CurrentProfessional professional(boolean enabled, List<WeeklySchedule> schedules) {
		return new CurrentProfessional(202, "Martin", "Rivas", enabled,
				new CurrentProfessional.Category(101, "Cardiologia", true), schedules);
	}
}
