package ar.edu.um.prog2.appointmentreservation.user.infrastructure.web.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;

class RegisterUserRequestTest {

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
	void acceptsValidRegistration() {
		assertThat(validator.validate(request(Map.of()))).isEmpty();
	}

	@ParameterizedTest
	@MethodSource("invalidFields")
	void rejectsInvalidFields(String field, String value) {
		Map<String, String> changes = new HashMap<>();
		changes.put(field, value);
		assertThat(validator.validate(request(changes)))
				.anySatisfy(violation -> assertThat(violation.getPropertyPath().toString()).isEqualTo(field));
	}

	static Stream<Arguments> invalidFields() {
		return Stream.of(
				Arguments.of("login", null), Arguments.of("login", ""), Arguments.of("login", " "),
				Arguments.of("login", "x".repeat(51)), Arguments.of("login", "a b"),
				Arguments.of("login", "usuario/otro"), Arguments.of("login", "josé"),
				Arguments.of("password", null), Arguments.of("password", ""),
				Arguments.of("password", "abc"), Arguments.of("password", " ".repeat(12)),
				Arguments.of("password", "x".repeat(101)),
				Arguments.of("firstName", "x".repeat(51)), Arguments.of("lastName", "x".repeat(51)),
				Arguments.of("email", null), Arguments.of("email", ""), Arguments.of("email", " "),
				Arguments.of("email", "a@b"), Arguments.of("email", "not-an-email"),
				Arguments.of("email", "a b@example.com"), Arguments.of("email", maximumEmail() + "x"),
				Arguments.of("imageUrl", "x".repeat(257)),
				Arguments.of("langKey", ""), Arguments.of("langKey", " "),
				Arguments.of("langKey", "e"), Arguments.of("langKey", "x".repeat(11)));
	}

	@ParameterizedTest
	@ValueSource(strings = {"a", "x.y_z-1", "USER@EXAMPLE.COM", "a+b@example.com"})
	void acceptsJHipsterCompatibleLogins(String login) {
		assertThat(validator.validate(request(Map.of("login", login)))).isEmpty();
	}

	@Test
	void acceptsMaximumLengths() {
		RegisterUserRequest request = request(Map.of(
				"login", "x".repeat(50), "password", "x".repeat(100),
				"firstName", "x".repeat(50), "lastName", "x".repeat(50),
				"email", maximumEmail(), "imageUrl", "x".repeat(256), "langKey", "x".repeat(10)));
		assertThat(maximumEmail()).hasSize(254);
		assertThat(validator.validate(request)).isEmpty();
	}

	@Test
	void acceptsMinimumLengthsAndEmptyOptionalFields() {
		assertThat(validator.validate(request(Map.of(
				"login", "a", "password", "abcd", "email", "a@b.c",
				"firstName", "", "lastName", "", "imageUrl", "", "langKey", "es")))).isEmpty();
	}

	@Test
	void acceptsNullOptionalFieldsAndDefaultsLanguage() {
		Map<String, String> changes = new HashMap<>();
		for (String field : new String[] {"firstName", "lastName", "imageUrl", "langKey"}) {
			changes.put(field, null);
		}
		RegisterUserRequest request = request(changes);
		assertThat(request.firstName()).isNull();
		assertThat(request.lastName()).isNull();
		assertThat(request.imageUrl()).isNull();
		assertThat(request.langKey()).isEqualTo("es");
		assertThat(validator.validate(request)).isEmpty();
	}

	@Test
	void preservesPasswordAndIdentityInputForTheApplicationLayer() {
		RegisterUserRequest request = request(Map.of(
				"password", "  a password  ", "login", "User", "email", "User@Example.COM"));
		assertThat(validator.validate(request)).isEmpty();
		assertThat(request.password()).isEqualTo("  a password  ");
		assertThat(request.login()).isEqualTo("User");
		assertThat(request.email()).isEqualTo("User@Example.COM");
		assertThat(request.toString()).isEqualTo("RegisterUserRequest[redacted]");
	}

	private static String maximumEmail() {
		return "a".repeat(64) + "@" + "b".repeat(63) + "." + "c".repeat(63) + "." + "d".repeat(61);
	}

	private static RegisterUserRequest request(Map<String, String> changes) {
		Map<String, String> fields = new HashMap<>(Map.of(
				"login", "user", "password", "test-password", "firstName", "Ana", "lastName", "Perez",
				"email", "user@example.com", "imageUrl", "https://example.com/avatar.png", "langKey", "es"));
		fields.putAll(changes);
		return new RegisterUserRequest(fields.get("login"), fields.get("password"), fields.get("firstName"),
				fields.get("lastName"), fields.get("email"), fields.get("imageUrl"), fields.get("langKey"));
	}
}
