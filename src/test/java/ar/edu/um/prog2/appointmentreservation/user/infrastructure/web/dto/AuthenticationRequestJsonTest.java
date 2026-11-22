package ar.edu.um.prog2.appointmentreservation.user.infrastructure.web.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;
import org.springframework.http.MediaType;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.mock.http.MockHttpInputMessage;
import org.springframework.mock.http.MockHttpOutputMessage;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import ar.edu.um.prog2.appointmentreservation.user.application.usecase.RegisterUserUseCase;
import ar.edu.um.prog2.appointmentreservation.user.infrastructure.web.UserRegistrationController;
import ar.edu.um.prog2.appointmentreservation.user.infrastructure.security.FinalUserHttpSecurityConfiguration;

// Loads the existing MVC slice; no authentication endpoint is introduced by this test.
@WebMvcTest(controllers = UserRegistrationController.class,
		excludeAutoConfiguration = UserDetailsServiceAutoConfiguration.class,
		properties = "spring.jackson.deserialization.fail-on-unknown-properties=false")
@Import(FinalUserHttpSecurityConfiguration.class)
class AuthenticationRequestJsonTest {

	private static final String VALID_JSON = """
			{"username":"User.Name","password":"  AbCd password  "}
			""";

	@Autowired
	private RequestMappingHandlerAdapter handlerAdapter;

	@MockitoBean
	private RegisterUserUseCase registration;

	@MockitoBean
	private JwtDecoder jwtDecoder;

	private final JsonMapper permissiveMapper = JsonMapper.builder()
			.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();

	private JacksonJsonHttpMessageConverter mvcConverter;

	@BeforeEach
	void findTheActualMvcJsonConverter() {
		mvcConverter = handlerAdapter.getMessageConverters().stream()
				.filter(JacksonJsonHttpMessageConverter.class::isInstance)
				.map(JacksonJsonHttpMessageConverter.class::cast).findFirst().orElseThrow();
		assertThat(mvcConverter.getMapper().isEnabled(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES))
				.isFalse();
	}

	@Test
	void deserializesPasswordExactlyAndDefaultsOmittedRememberMe() throws Exception {
		for (AuthenticationRequest request : List.of(
				permissiveMapper.readValue(VALID_JSON, AuthenticationRequest.class), readWithMvc(VALID_JSON))) {
			assertThat(request.username()).isEqualTo("User.Name");
			assertThat(request.password()).isEqualTo("  AbCd password  ");
			assertThat(request.rememberMe()).isFalse();
		}
	}

	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	void deserializesExplicitRememberMe(boolean rememberMe) throws Exception {
		String json = VALID_JSON.replace("}", ",\"rememberMe\":" + rememberMe + "}");
		assertThat(readWithMvc(json).rememberMe()).isEqualTo(rememberMe);
	}

	@ParameterizedTest
	@ValueSource(strings = {
			"{\"password\":\"abcd\"}", "{\"username\":null,\"password\":\"abcd\"}"
	})
	void leavesMissingOrNullUsernameForJakartaValidation(String json) throws Exception {
		assertThat(readWithMvc(json).username()).isNull();
	}

	@ParameterizedTest
	@ValueSource(strings = {
			"{\"username\":\"user\"}", "{\"username\":\"user\",\"password\":null}"
	})
	void leavesMissingOrNullPasswordForJakartaValidation(String json) throws Exception {
		assertThat(readWithMvc(json).password()).isNull();
	}

	@Test
	void rejectsExplicitNullRememberMe() {
		String json = VALID_JSON.replace("}", ",\"rememberMe\":null}");
		assertThatThrownBy(() -> readWithMvc(json)).isInstanceOf(HttpMessageNotReadableException.class);
		assertThatThrownBy(() -> permissiveMapper.readValue(json, AuthenticationRequest.class))
				.isInstanceOf(RuntimeException.class);
	}

	@ParameterizedTest
	@ValueSource(strings = {
			"\"id\":42", "\"activated\":true", "\"authorities\":[\"ROLE_ADMIN\"]",
			"\"passwordHash\":\"private-extra-input\"", "\"createdBy\":\"admin\"",
			"\"createdDate\":\"2026-01-01T00:00:00Z\"", "\"lastModifiedBy\":\"admin\"",
			"\"lastModifiedDate\":\"2026-01-01T00:00:00Z\"", "\"unexpected\":null",
			"\"unexpected\":{\"nested\":\"private-extra-input\"}", "\"login\":\"user\"",
			"\"email\":\"user@example.test\"", "\"USERNAME\":\"user\""
	})
	void rejectsUnknownFieldsWithBothPermissiveAndSpringMvcMappers(String property) {
		String json = VALID_JSON.replace("}", "," + property + "}");
		assertThatThrownBy(() -> permissiveMapper.readValue(json, AuthenticationRequest.class))
				.hasRootCauseInstanceOf(IllegalArgumentException.class)
				.hasRootCauseMessage("Unknown authentication property");
		assertThatThrownBy(() -> readWithMvc(json))
				.isInstanceOf(HttpMessageNotReadableException.class)
				.hasRootCauseInstanceOf(IllegalArgumentException.class)
				.hasRootCauseMessage("Unknown authentication property");
	}

	@Test
	void mvcSerializationAndToStringExcludePassword() throws Exception {
		AuthenticationRequest request = readWithMvc(VALID_JSON);
		MockHttpOutputMessage output = new MockHttpOutputMessage();
		mvcConverter.write(request, MediaType.APPLICATION_JSON, output);
		String mvcJson = output.getBodyAsString(StandardCharsets.UTF_8);
		for (String serialized : List.of(mvcJson, permissiveMapper.writeValueAsString(request))) {
			var body = mvcConverter.getMapper().readTree(serialized);
			assertThat(body.has("password")).isFalse();
			assertThat(body.get("username").asString()).isEqualTo("User.Name");
			assertThat(body.get("rememberMe").asBoolean()).isFalse();
			assertThat(body.size()).isEqualTo(2);
			assertThat(serialized).doesNotContain(request.password());
		}
		assertThat(request.toString()).isEqualTo("AuthenticationRequest[redacted]");
	}

	private AuthenticationRequest readWithMvc(String json) throws Exception {
		MockHttpInputMessage input = new MockHttpInputMessage(json.getBytes(StandardCharsets.UTF_8));
		input.getHeaders().setContentType(MediaType.APPLICATION_JSON);
		return (AuthenticationRequest) mvcConverter.read(AuthenticationRequest.class, input);
	}
}
