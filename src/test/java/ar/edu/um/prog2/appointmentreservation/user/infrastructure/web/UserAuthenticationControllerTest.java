package ar.edu.um.prog2.appointmentreservation.user.infrastructure.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import ar.edu.um.prog2.appointmentreservation.user.application.exception.AuthenticationUnavailableException;
import ar.edu.um.prog2.appointmentreservation.user.application.exception.InvalidCredentialsException;
import ar.edu.um.prog2.appointmentreservation.user.application.model.AuthenticationCommand;
import ar.edu.um.prog2.appointmentreservation.user.application.model.AuthenticationToken;
import ar.edu.um.prog2.appointmentreservation.user.application.usecase.AuthenticateUserUseCase;
import ar.edu.um.prog2.appointmentreservation.user.infrastructure.security.FinalUserHttpSecurityConfiguration;
import ar.edu.um.prog2.appointmentreservation.user.infrastructure.web.dto.AuthenticationResponse;

@WebMvcTest(controllers = UserAuthenticationController.class,
		excludeAutoConfiguration = UserDetailsServiceAutoConfiguration.class)
@Import(FinalUserHttpSecurityConfiguration.class)
@ExtendWith(OutputCaptureExtension.class)
class UserAuthenticationControllerTest {
	private static final String PASSWORD = "  Test-only HTTP password  ";
	private static final String TOKEN = "test-only-token-value";
	private static final String INPUT = "{\"username\":\"ALICE\",\"password\":\"" + PASSWORD + "\"}";

	@Autowired private MockMvc mvc;
	@MockitoBean private AuthenticateUserUseCase useCase;
	@MockitoBean private AuthenticationManager manager;

	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	void publicLoginReturnsTheSameTokenInBodyAndHeaderWithoutSessionOrLogs(boolean rememberMe, CapturedOutput output)
			throws Exception {
		when(useCase.authenticate(any())).thenReturn(new AuthenticationToken(TOKEN));
		String input = rememberMe ? INPUT.replace("}", ",\"rememberMe\":true}") : INPUT;
		mvc.perform(post("/api/authenticate").contentType(MediaType.APPLICATION_JSON).content(input))
				.andExpect(status().isOk()).andExpect(jsonPath("$.id_token").value(TOKEN))
				.andExpect(jsonPath("$.*").value(org.hamcrest.Matchers.hasSize(1)))
				.andExpect(header().string(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN))
				.andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
				.andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE))
				.andExpect(result -> assertThat(result.getRequest().getSession(false)).isNull());
		ArgumentCaptor<AuthenticationCommand> command = ArgumentCaptor.forClass(AuthenticationCommand.class);
		verify(useCase).authenticate(command.capture());
		assertThat(command.getValue().username()).isEqualTo("ALICE");
		assertThat(command.getValue().password()).isEqualTo(PASSWORD);
		assertThat(command.getValue().rememberMe()).isEqualTo(rememberMe);
		assertThat(output.getAll()).doesNotContain(PASSWORD, TOKEN);
		assertThat(new AuthenticationResponse(TOKEN).toString()).isEqualTo("AuthenticationResponse[redacted]");
	}

	@Test
	void credentialFailureReturnsGeneric401WithoutAChallengeOrSensitiveDetails(CapturedOutput output) throws Exception {
		when(useCase.authenticate(any())).thenThrow(new InvalidCredentialsException());
		String body = mvc.perform(post("/api/authenticate").contentType(MediaType.APPLICATION_JSON).content(INPUT))
				.andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"))
				.andExpect(jsonPath("$.detail").value("Invalid credentials"))
				.andExpect(header().doesNotExist(HttpHeaders.AUTHORIZATION))
				.andExpect(header().doesNotExist(HttpHeaders.WWW_AUTHENTICATE))
				.andReturn().getResponse().getContentAsString();
		assertThat(body).doesNotContain(PASSWORD, "ALICE", "disabled", "unknown", "passwordHash");
		assertThat(output.getAll()).doesNotContain(PASSWORD);
	}

	@ParameterizedTest
	@MethodSource("invalidInputs")
	void rejectsInvalidBodiesBeforeCallingAuthentication(String input, String code, CapturedOutput output) throws Exception {
		String body = mvc.perform(post("/api/authenticate").contentType(MediaType.APPLICATION_JSON).content(input))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(code))
				.andReturn().getResponse().getContentAsString();
		assertThat(body).doesNotContain(PASSWORD, "private-extra-input");
		assertThat(output.getAll()).doesNotContain(PASSWORD, "private-extra-input");
		verifyNoInteractions(useCase);
	}

	static Stream<Arguments> invalidInputs() {
		return Stream.of(Arguments.of("{}", "VALIDATION_ERROR"),
				Arguments.of("{\"username\":\"alice\",\"password\":\"abc\"}", "VALIDATION_ERROR"),
				Arguments.of(INPUT.replace("ALICE", " "), "VALIDATION_ERROR"),
				Arguments.of(INPUT.replace("}", ",\"rememberMe\":null}"), "INVALID_REQUEST"),
				Arguments.of(INPUT.replace("}", ",\"authorities\":[\"ROLE_ADMIN\"]}"), "INVALID_REQUEST"),
				Arguments.of(INPUT.replace("}", ",\"userId\":42}"), "INVALID_REQUEST"),
				Arguments.of(INPUT.replace("}", ",\"exp\":9999999999}"), "INVALID_REQUEST"),
				Arguments.of(INPUT.replace("}", ",\"passwordHash\":\"private-extra-input\"}"), "INVALID_REQUEST"),
				Arguments.of("{\"password\":\"private-extra-input\",", "INVALID_REQUEST"));
	}

	@Test
	void missingBodyIsRejected() throws Exception {
		mvc.perform(post("/api/authenticate").contentType(MediaType.APPLICATION_JSON))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
		verifyNoInteractions(useCase);
	}

	@Test
	void infrastructureFailureReturns503() throws Exception {
		when(useCase.authenticate(any())).thenThrow(new AuthenticationUnavailableException());
		mvc.perform(post("/api/authenticate").contentType(MediaType.APPLICATION_JSON).content(INPUT))
				.andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("AUTHENTICATION_UNAVAILABLE"));
	}

	@Test
	void unexpectedErrorsDoNotEchoOrLogExceptionMessages(CapturedOutput output) throws Exception {
		when(useCase.authenticate(any())).thenThrow(new IllegalStateException("private-storage-detail " + PASSWORD));
		String body = mvc.perform(post("/api/authenticate").contentType(MediaType.APPLICATION_JSON).content(INPUT))
				.andExpect(status().isInternalServerError()).andExpect(jsonPath("$.code").value("AUTHENTICATION_FAILED"))
				.andReturn().getResponse().getContentAsString();
		assertThat(body).doesNotContain(PASSWORD, "private-storage-detail");
		assertThat(output.getAll()).doesNotContain(PASSWORD, "private-storage-detail");
	}
}
