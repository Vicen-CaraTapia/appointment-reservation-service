package ar.edu.um.prog2.appointmentreservation.user.infrastructure.web.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.Stream;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;

class AuthenticationRequestTest {

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

	@Test
	void acceptsValidAuthenticationInput() {
		assertThat(validator.validate(new AuthenticationRequest("User.Name", "test-only-password", false)))
				.isEmpty();
	}

	@ParameterizedTest
	@MethodSource("invalidFields")
	void rejectsInvalidFields(String username, String password, String field) {
		assertThat(validator.validate(new AuthenticationRequest(username, password, false)))
				.anySatisfy(violation -> assertThat(violation.getPropertyPath().toString()).isEqualTo(field));
	}

	static Stream<Arguments> invalidFields() {
		return Stream.of(
				Arguments.of(null, "abcd", "username"), Arguments.of("", "abcd", "username"),
				Arguments.of(" ", "abcd", "username"), Arguments.of("\t\n", "abcd", "username"),
				Arguments.of("x".repeat(255), "abcd", "username"),
				Arguments.of("user", null, "password"), Arguments.of("user", "", "password"),
				Arguments.of("user", " ".repeat(4), "password"),
				Arguments.of("user", "\t\n  ", "password"),
				Arguments.of("user", "abc", "password"),
				Arguments.of("user", "x".repeat(101), "password"));
	}

	@Test
	void acceptsMinimumLengths() {
		assertThat(validator.validate(new AuthenticationRequest("a", "abcd", false))).isEmpty();
	}

	@Test
	void acceptsMaximumLengths() {
		assertThat(validator.validate(new AuthenticationRequest("x".repeat(254), "x".repeat(100), true)))
				.isEmpty();
	}

	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	void preservesTheRememberMeChoice(boolean rememberMe) {
		AuthenticationRequest request = new AuthenticationRequest("user", "abcd", rememberMe);
		assertThat(request.rememberMe()).isEqualTo(rememberMe);
		assertThat(validator.validate(request)).isEmpty();
	}

	@ParameterizedTest
	@ValueSource(strings = {"  AbCd  ", "\tAbCd\t", "\nAbCd\n", "MixedCASE-password"})
	void preservesPasswordWhitespaceAndCaseWithoutChangingUsername(String password) {
		AuthenticationRequest request = new AuthenticationRequest("User.Name", password, false);
		assertThat(validator.validate(request)).isEmpty();
		assertThat(request.username()).isEqualTo("User.Name");
		assertThat(request.password()).isEqualTo(password);
		assertThat(request.toString()).isEqualTo("AuthenticationRequest[redacted]");
	}

	@Test
	void validationExceptionMessagesDoNotContainRejectedCredentialValues() {
		String username = "private-test-username-".repeat(13);
		String password = "private-test-password-".repeat(5);
		var violations = validator.validate(new AuthenticationRequest(username, password, false));
		assertThat(violations).hasSize(2);
		assertThat(violations).allSatisfy(violation ->
				assertThat(violation.getMessage()).doesNotContain(username, password));
		assertThat(new ConstraintViolationException(violations).getMessage()).doesNotContain(username, password);
	}
}
