package ar.edu.um.prog2.appointmentreservation.reservation.infrastructure.web.dto;

import java.util.stream.Stream;

import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class ConfirmReservationRequestTest {

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
	@ValueSource(ints = {2, 100})
	void acceptsBothLengthBoundariesForBothPatientNames(int length) {
		var request = new ConfirmReservationRequest("a".repeat(length), "b".repeat(length));
		assertThat(validator.validate(request)).isEmpty();
		assertThat(request.patientFirstName()).hasSize(length);
		assertThat(request.patientLastName()).hasSize(length);
	}

	@ParameterizedTest
	@MethodSource("invalidNames")
	void rejectsEachInvalidNameWithoutExposingItInValidationMessages(String value) {
		var firstErrors = validator.validate(new ConfirmReservationRequest(value, "Perez"));
		var lastErrors = validator.validate(new ConfirmReservationRequest("Ana", value));
		assertThat(firstErrors).isNotEmpty().allSatisfy(error ->
				assertThat(error.getPropertyPath().toString()).isEqualTo("patientFirstName"));
		assertThat(lastErrors).isNotEmpty().allSatisfy(error ->
				assertThat(error.getPropertyPath().toString()).isEqualTo("patientLastName"));
		assertThat(new ConstraintViolationException(firstErrors).getMessage()).doesNotContain("private-patient-input");
		assertThat(new ConstraintViolationException(lastErrors).getMessage()).doesNotContain("private-patient-input");
	}

	static Stream<String> invalidNames() {
		return Stream.of(null, "", " ", "\t\n", "A", "private-patient-input".repeat(6));
	}

	@Test
	void reportsBothMissingFieldsAndRedactsItsStringRepresentation() {
		var violations = validator.validate(new ConfirmReservationRequest(null, null));
		assertThat(violations).extracting(error -> error.getPropertyPath().toString())
				.containsExactlyInAnyOrder("patientFirstName", "patientLastName");
		assertThat(new ConfirmReservationRequest("PrivateFirstName", "PrivateLastName").toString())
				.isEqualTo("ConfirmReservationRequest[redacted]");
	}

	@ParameterizedTest
	@ValueSource(strings = {" Ana María ", "O'Connor", "María-José", "123", "李王"})
	void preservesNamesWithoutTrimmingOrInventingAnAlphabeticPattern(String name) {
		var request = new ConfirmReservationRequest(name, name);
		assertThat(validator.validate(request)).isEmpty();
		assertThat(request.patientFirstName()).isEqualTo(name);
		assertThat(request.patientLastName()).isEqualTo(name);
	}
}
