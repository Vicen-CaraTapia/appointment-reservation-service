package ar.edu.um.prog2.appointmentreservation.availability.application.service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import ar.edu.um.prog2.appointmentreservation.professional.application.model.CurrentProfessional.WeeklySchedule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AvailableAppointmentSlotsCalculatorTest {

	private static final LocalDate MONDAY = LocalDate.of(2026, 9, 14);
	private final AvailableAppointmentSlotsCalculator calculator = new AvailableAppointmentSlotsCalculator();

	@Test
	void calculatesTheIssueExampleAndExcludesOnlyOccupiedPositions() {
		var schedules = List.of(schedule("09:00", "12:00", 30));
		var occupied = Set.of(LocalTime.of(9, 0), LocalTime.of(10, 30));
		assertThat(calculator.calculate(MONDAY, schedules, occupied))
				.containsExactly(LocalTime.of(9, 30), LocalTime.of(10, 0), LocalTime.of(11, 0), LocalTime.of(11, 30));
	}

	@Test
	void includesAFullSlotEndingExactlyAtTheScheduleEndAndNeverStartsAtTheEnd() {
		assertThat(calculator.calculate(MONDAY, List.of(schedule("09:00", "10:00", 30)), Set.of()))
				.containsExactly(LocalTime.of(9, 0), LocalTime.of(9, 30));
	}

	@ParameterizedTest
	@CsvSource({"15,4", "20,3", "30,2", "60,1"})
	void takesTheSlotDurationFromEachSchedule(int duration, int expectedSlots) {
		var result = calculator.calculate(MONDAY, List.of(schedule("09:00", "10:00", duration)), Set.of());
		assertThat(result).hasSize(expectedSlots);
		for (int index = 0; index < expectedSlots; index++) {
			assertThat(result.get(index)).isEqualTo(LocalTime.of(9, 0).plusMinutes((long) index * duration));
		}
	}

	@Test
	void doesNotGenerateAPartialSlotAtTheEndOfAnUnevenInterval() {
		assertThat(calculator.calculate(MONDAY, List.of(schedule("09:00", "10:10", 30)), Set.of()))
				.containsExactly(LocalTime.of(9, 0), LocalTime.of(9, 30));
	}

	@ParameterizedTest
	@ValueSource(ints = {61, Integer.MAX_VALUE})
	void anIntervalShorterThanOneSlotProducesNoSlotsWithoutOverflow(int duration) {
		assertThat(calculator.calculate(MONDAY, List.of(schedule("09:00", "10:00", duration)), Set.of())).isEmpty();
	}

	@ParameterizedTest
	@EnumSource(DayOfWeek.class)
	void selectsTheWeekdayFromTheRequestedDate(DayOfWeek day) {
		var date = MONDAY.plusDays(day.getValue() - 1);
		var schedules = List.of(
				new WeeklySchedule(1, day, LocalTime.of(9, 0), LocalTime.of(10, 0), 30, true));
		assertThat(calculator.calculate(date, schedules, Set.of()))
				.containsExactly(LocalTime.of(9, 0), LocalTime.of(9, 30));
		assertThat(calculator.calculate(date.plusDays(1), schedules, Set.of())).isEmpty();
	}

	@Test
	void ignoresDisabledRulesAndRulesForOtherWeekdays() {
		var schedules = List.of(
				new WeeklySchedule(1, DayOfWeek.MONDAY, LocalTime.of(8, 0), LocalTime.of(9, 0), 30, false),
				new WeeklySchedule(2, DayOfWeek.TUESDAY, LocalTime.of(8, 0), LocalTime.of(9, 0), 30, true),
				schedule("09:00", "10:00", 30));
		assertThat(calculator.calculate(MONDAY, schedules, Set.of()))
				.containsExactly(LocalTime.of(9, 0), LocalTime.of(9, 30));
	}

	@Test
	void combinesSeparateIntervalsInChronologicalOrderUsingTheirOwnDurations() {
		var schedules = List.of(schedule("15:00", "16:00", 30), schedule("09:00", "10:00", 20));
		assertThat(calculator.calculate(MONDAY, schedules, Set.of()))
				.containsExactly(LocalTime.of(9, 0), LocalTime.of(9, 20), LocalTime.of(9, 40),
						LocalTime.of(15, 0), LocalTime.of(15, 30));
	}

	@Test
	void repeatedPositionsAppearOnlyOnceAndRemainExcludedWhenOccupied() {
		var schedules = List.of(schedule("09:00", "10:30", 30), schedule("09:30", "11:00", 30));
		assertThat(calculator.calculate(MONDAY, schedules, Set.of(LocalTime.of(9, 30))))
				.containsExactly(LocalTime.of(9, 0), LocalTime.of(10, 0), LocalTime.of(10, 30));
	}

	@Test
	void unrelatedOccupiedStartTimesDoNotRemoveOtherSlots() {
		assertThat(calculator.calculate(MONDAY, List.of(schedule("09:00", "10:00", 30)),
				Set.of(LocalTime.of(8, 0), LocalTime.of(9, 15), LocalTime.of(10, 0))))
				.containsExactly(LocalTime.of(9, 0), LocalTime.of(9, 30));
	}

	@Test
	void allOccupiedOrMissingSchedulesProduceAnEmptyResult() {
		assertThat(calculator.calculate(MONDAY, List.of(), Set.of())).isEmpty();
		assertThat(calculator.calculate(MONDAY, List.of(schedule("09:00", "10:00", 30)),
				Set.of(LocalTime.of(9, 0), LocalTime.of(9, 30)))).isEmpty();
	}

	@ParameterizedTest
	@ValueSource(ints = {0, -1, Integer.MIN_VALUE})
	void rejectsNonpositiveDurationsInApplicableSchedules(int duration) {
		assertThatThrownBy(() -> calculator.calculate(MONDAY, List.of(schedule("09:00", "10:00", duration)), Set.of()))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("Applicable weekly schedule must have a positive slot duration and start before end");
	}

	@ParameterizedTest
	@CsvSource({"09:00,09:00", "10:00,09:00", "23:00,01:00"})
	void rejectsEmptyReversedAndOvernightApplicableIntervals(String start, String end) {
		assertThatThrownBy(() -> calculator.calculate(MONDAY, List.of(schedule(start, end, 30)), Set.of()))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void irrelevantInvalidRulesDoNotPreventCalculatingTheRequestedDay() {
		var schedules = List.of(
				new WeeklySchedule(1, DayOfWeek.TUESDAY, LocalTime.NOON, LocalTime.NOON, 0, true),
				new WeeklySchedule(2, DayOfWeek.MONDAY, LocalTime.NOON, LocalTime.NOON, -1, false),
				schedule("09:00", "09:30", 30));
		assertThat(calculator.calculate(MONDAY, schedules, Set.of())).containsExactly(LocalTime.of(9, 0));
	}

	@Test
	void doesNotReturnPartialAvailabilityWhenALaterApplicableRuleIsInvalid() {
		var schedules = List.of(schedule("09:00", "10:00", 30), schedule("15:00", "16:00", 0));
		assertThatThrownBy(() -> calculator.calculate(MONDAY, schedules, Set.of()))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void handlesLateIntervalsWithoutWrappingToTheNextDay() {
		assertThat(calculator.calculate(MONDAY, List.of(schedule("23:00", "23:59:59.999999999", 30)), Set.of()))
				.containsExactly(LocalTime.of(23, 0));
	}

	@Test
	void preservesTheSchedulesSecondAndNanosecondPrecision() {
		assertThat(calculator.calculate(MONDAY,
				List.of(schedule("09:00:15.123456789", "10:00:15.123456789", 30)),
				Set.of(LocalTime.parse("09:30:15.123456789"))))
				.containsExactly(LocalTime.parse("09:00:15.123456789"));
	}

	@Test
	void keepsInputsUnchangedAndReturnsAnImmutableList() {
		var schedules = new ArrayList<>(List.of(schedule("09:00", "10:00", 30)));
		var occupied = new HashSet<>(Set.of(LocalTime.of(9, 30)));
		var originalSchedules = List.copyOf(schedules);
		var originalOccupied = Set.copyOf(occupied);
		var result = calculator.calculate(MONDAY, schedules, occupied);
		assertThat(schedules).containsExactlyElementsOf(originalSchedules);
		assertThat(occupied).containsExactlyInAnyOrderElementsOf(originalOccupied);
		assertThatThrownBy(() -> result.add(LocalTime.NOON)).isInstanceOf(UnsupportedOperationException.class);
	}

	@Test
	void requiresAllInputsRatherThanTreatingMissingOccupanciesAsFreeSlots() {
		assertThatThrownBy(() -> calculator.calculate(null, List.of(), Set.of())).isInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> calculator.calculate(MONDAY, null, Set.of())).isInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> calculator.calculate(MONDAY, List.of(), null)).isInstanceOf(NullPointerException.class);
	}

	private static WeeklySchedule schedule(String start, String end, int duration) {
		return new WeeklySchedule(1, DayOfWeek.MONDAY, LocalTime.parse(start), LocalTime.parse(end), duration, true);
	}
}
