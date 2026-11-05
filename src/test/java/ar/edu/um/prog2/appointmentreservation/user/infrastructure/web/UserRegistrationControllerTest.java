package ar.edu.um.prog2.appointmentreservation.user.infrastructure.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import tools.jackson.databind.json.JsonMapper;

import ar.edu.um.prog2.appointmentreservation.user.application.exception.DuplicateUserException;
import ar.edu.um.prog2.appointmentreservation.user.application.exception.DuplicateUserException.Field;
import ar.edu.um.prog2.appointmentreservation.user.application.model.RegisteredUser;
import ar.edu.um.prog2.appointmentreservation.user.application.model.RegisterUserCommand;
import ar.edu.um.prog2.appointmentreservation.user.application.usecase.RegisterUserUseCase;
import ar.edu.um.prog2.appointmentreservation.user.infrastructure.security.FinalUserHttpSecurityConfiguration;

@WebMvcTest(controllers = UserRegistrationController.class,
		excludeAutoConfiguration = UserDetailsServiceAutoConfiguration.class)
@Import(FinalUserHttpSecurityConfiguration.class)
@ExtendWith(OutputCaptureExtension.class)
class UserRegistrationControllerTest {

	private static final String PASSWORD = "  test-only API passphrase  ";

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JsonMapper json;

	@MockitoBean
	private RegisterUserUseCase useCase;

	@MockitoBean
	private JwtDecoder jwtDecoder;

	@Test
	void registersWithoutAuthenticationAndReturnsOnlyThePublicProfile(CapturedOutput output) throws Exception {
		when(useCase.register(any())).thenReturn(new RegisteredUser(42L, "user", "Ana", "Perez",
				"user@example.test", null, "es", true, Set.of("ROLE_USER")));
		String body = mvc.perform(post("/api/register").contentType(MediaType.APPLICATION_JSON)
				.content(json.writeValueAsString(validInput())))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.login").value("user"))
				.andExpect(jsonPath("$.firstName").value("Ana"))
				.andExpect(jsonPath("$.lastName").value("Perez"))
				.andExpect(jsonPath("$.email").value("user@example.test"))
				.andExpect(jsonPath("$.langKey").value("es"))
				.andReturn().getResponse().getContentAsString();
		assertThat(json.readTree(body).size()).isEqualTo(6);
		assertThat(json.readTree(body).get("imageUrl").isNull()).isTrue();
		assertThat(body).doesNotContain(PASSWORD, "password", "id", "activated", "authorities", "createdBy",
				"createdDate", "lastModifiedBy", "lastModifiedDate");
		ArgumentCaptor<RegisterUserCommand> command = ArgumentCaptor.forClass(RegisterUserCommand.class);
		verify(useCase).register(command.capture());
		assertThat(command.getValue().login()).isEqualTo("User");
		assertThat(command.getValue().email()).isEqualTo("USER@example.test");
		assertThat(command.getValue().password()).isEqualTo(PASSWORD);
		assertThat(command.getValue().firstName()).isEqualTo("Ana");
		assertThat(command.getValue().lastName()).isEqualTo("Perez");
		assertThat(command.getValue().imageUrl()).isNull();
		assertThat(command.getValue().langKey()).isEqualTo("es");
		assertThat(output.getAll()).doesNotContain(PASSWORD);
	}

	@ParameterizedTest
	@MethodSource("invalidFields")
	void rejectsInvalidInputBeforeCallingTheUseCase(String field, String value, CapturedOutput output)
			throws Exception {
		Map<String, Object> input = validInput();
		input.put(field, value);
		String body = mvc.perform(post("/api/register").contentType(MediaType.APPLICATION_JSON)
				.content(json.writeValueAsString(input)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
				.andExpect(jsonPath("$.fields[0]").value(field))
				.andReturn().getResponse().getContentAsString();
		assertThat(body).doesNotContain(PASSWORD);
		assertThat(output.getAll()).doesNotContain(PASSWORD);
		verifyNoInteractions(useCase);
	}

	static Stream<Arguments> invalidFields() {
		return Stream.of(Arguments.of("login", "invalid/login"), Arguments.of("password", "abc"),
				Arguments.of("firstName", "x".repeat(51)), Arguments.of("lastName", "x".repeat(51)),
				Arguments.of("email", "not-an-email"), Arguments.of("imageUrl", "x".repeat(257)),
				Arguments.of("langKey", ""), Arguments.of("email", null));
	}

	@ParameterizedTest
	@ValueSource(strings = {"id", "activated", "authorities", "passwordHash", "createdBy", "createdDate",
			"lastModifiedBy", "lastModifiedDate", "unexpected"})
	void rejectsAdministrativeAndUnknownFields(String field, CapturedOutput output) throws Exception {
		Map<String, Object> input = validInput();
		input.put(field, "private-extra-input");
		String body = mvc.perform(post("/api/register").contentType(MediaType.APPLICATION_JSON)
				.content(json.writeValueAsString(input)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
				.andReturn().getResponse().getContentAsString();
		assertThat(body).doesNotContain(PASSWORD, "private-extra-input");
		assertThat(output.getAll()).doesNotContain(PASSWORD, "private-extra-input");
		verifyNoInteractions(useCase);
	}

	@Test
	void rejectsMalformedJsonWithoutEchoingItsContent(CapturedOutput output) throws Exception {
		String body = mvc.perform(post("/api/register").contentType(MediaType.APPLICATION_JSON)
				.content("{\"password\":\"private-malformed-input\","))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
				.andReturn().getResponse().getContentAsString();
		assertThat(body).doesNotContain("private-malformed-input");
		assertThat(output.getAll()).doesNotContain("private-malformed-input");
		verifyNoInteractions(useCase);
	}

	@Test
	void rejectsAnAbsentBody() throws Exception {
		mvc.perform(post("/api/register").contentType(MediaType.APPLICATION_JSON))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
		verifyNoInteractions(useCase);
	}

	@ParameterizedTest
	@EnumSource(Field.class)
	void mapsDuplicatesToTheCompatibleBadRequestStatus(Field field) throws Exception {
		when(useCase.register(any())).thenThrow(new DuplicateUserException(field));
		mvc.perform(post("/api/register").contentType(MediaType.APPLICATION_JSON)
				.content(json.writeValueAsString(validInput())))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value(field == Field.LOGIN
						? "LOGIN_ALREADY_EXISTS" : "EMAIL_ALREADY_EXISTS"));
	}

	@Test
	void returnsASafeServerErrorWithoutLoggingStorageDetails(CapturedOutput output) throws Exception {
		when(useCase.register(any())).thenThrow(new DataIntegrityViolationException(
				"private-storage-diagnostic " + PASSWORD + " $argon2id$private-hash"));
		String body = mvc.perform(post("/api/register").contentType(MediaType.APPLICATION_JSON)
				.content(json.writeValueAsString(validInput())))
				.andExpect(status().isInternalServerError())
				.andExpect(jsonPath("$.code").value("REGISTRATION_FAILED"))
				.andReturn().getResponse().getContentAsString();
		assertThat(body).doesNotContain(PASSWORD, "private-storage-diagnostic", "$argon2id$private-hash");
		assertThat(output.getAll()).contains("Registration failed (DataIntegrityViolationException)")
				.doesNotContain(PASSWORD, "private-storage-diagnostic", "$argon2id$private-hash");
	}

	@Test
	void preservesUnsupportedMediaTypeAndRejectsNonPublicMethods() throws Exception {
		mvc.perform(post("/api/register").contentType(MediaType.TEXT_PLAIN).content("text"))
				.andExpect(status().isUnsupportedMediaType());
		mvc.perform(get("/api/register")).andExpect(status().isUnauthorized());
		verifyNoInteractions(useCase);
	}

	private static Map<String, Object> validInput() {
		return new HashMap<>(Map.of("login", "User", "password", PASSWORD, "firstName", "Ana",
				"lastName", "Perez", "email", "USER@example.test"));
	}
}
