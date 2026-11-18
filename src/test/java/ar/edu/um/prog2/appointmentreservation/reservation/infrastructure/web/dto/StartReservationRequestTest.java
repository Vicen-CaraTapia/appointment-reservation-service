package ar.edu.um.prog2.appointmentreservation.reservation.infrastructure.web.dto;

import java.time.LocalDate;
import java.time.LocalTime;

import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class StartReservationRequestTest {

	private static final LocalDate DATE = LocalDate.of(2026, 11, 19);
	private static final LocalTime TIME = LocalTime.of(18, 0);
	private static ValidatorFactory factory;
	private static Validator validator;

	@BeforeAll
	static void createValidator() {
		factory = Validation.buildDefaultValidatorFactory();
		validator = factory.getValidator();
	}

	@AfterAll
	static void closeValidator() {
		factory.close();
	}

	@ParameterizedTest
	@ValueSource(longs = {Long.MIN_VALUE, -1, 0, 202, Long.MAX_VALUE})
	void preservesTheContractualInt64RangeWithoutInventingAPositiveIdConstraint(long id) {
		var request = new StartReservationRequest(id, DATE, TIME);
		assertThat(validator.validate(request)).isEmpty();
		assertThat(request.professionalId()).isEqualTo(id);
	}

	@ParameterizedTest
	@ValueSource(strings = {"professionalId", "date", "startTime"})
	void eachSelectionFieldIsRequired(String missing) {
		var request = new StartReservationRequest(missing.equals("professionalId") ? null : 202L,
				missing.equals("date") ? null : DATE, missing.equals("startTime") ? null : TIME);
		assertThat(validator.validate(request)).singleElement().satisfies(violation ->
				assertThat(violation.getPropertyPath().toString()).isEqualTo(missing));
	}

	@Test
	void reportsAllMissingFieldsWithoutEchoingRejectedValues() {
		var violations = validator.validate(new StartReservationRequest(null, null, null));
		assertThat(violations).extracting(violation -> violation.getPropertyPath().toString())
				.containsExactlyInAnyOrder("professionalId", "date", "startTime");
		assertThat(new ConstraintViolationException(violations).getMessage()).doesNotContain("null");
	}

	@Test
	void preservesDateAndTimeForBusinessValidationWithoutAssumingAvailabilityOrChangingPrecision() {
		// Calendar validity belongs here; agenda, occupancy and expiration rules belong to the use case/catedra.
		var leapDay = LocalDate.of(2028, 2, 29);
		var preciseTime = LocalTime.of(18, 0, 1, 123456789);
		var request = new StartReservationRequest(202L, leapDay, preciseTime);
		assertThat(validator.validate(request)).isEmpty();
		assertThat(request.date()).isEqualTo(leapDay);
		assertThat(request.startTime()).isEqualTo(preciseTime);
		assertThat(request.toString()).isEqualTo("StartReservationRequest[redacted]");
	}
}
