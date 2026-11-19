package ar.edu.um.prog2.appointmentreservation.reservation.infrastructure.web.dto;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.mock.http.MockHttpInputMessage;
import org.springframework.mock.http.MockHttpOutputMessage;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import ar.edu.um.prog2.appointmentreservation.reservation.domain.model.ReservationProcessStatus;
import ar.edu.um.prog2.appointmentreservation.user.application.usecase.RegisterUserUseCase;
import ar.edu.um.prog2.appointmentreservation.user.infrastructure.security.FinalUserHttpSecurityConfiguration;
import ar.edu.um.prog2.appointmentreservation.user.infrastructure.web.UserRegistrationController;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// Exercises the existing MVC converter without adding the confirmation endpoint in this increment.
@WebMvcTest(controllers = UserRegistrationController.class,
		excludeAutoConfiguration = UserDetailsServiceAutoConfiguration.class,
		properties = "spring.jackson.deserialization.fail-on-unknown-properties=false")
@Import(FinalUserHttpSecurityConfiguration.class)
class ConfirmReservationRequestJsonTest {

	private static final String VALID_JSON = """
			{"patientFirstName":" Ana María ","patientLastName":" Pérez "}
			""";
	private static ValidatorFactory factory;
	private static Validator validator;
	private final JsonMapper permissiveMapper = JsonMapper.builder()
			.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();
	@Autowired private RequestMappingHandlerAdapter handlerAdapter;
	@MockitoBean private RegisterUserUseCase registration;
	@MockitoBean private JwtDecoder jwtDecoder;
	private JacksonJsonHttpMessageConverter mvcConverter;

	@BeforeAll
	static void createValidator() {
		factory = Validation.buildDefaultValidatorFactory();
		validator = factory.getValidator();
	}

	@AfterAll
	static void closeValidator() {
		factory.close();
	}

	@BeforeEach
	void locatesTheActualMvcConverterAndChecksItsPermissiveUnknownFieldSetting() {
		mvcConverter = handlerAdapter.getMessageConverters().stream()
				.filter(JacksonJsonHttpMessageConverter.class::isInstance)
				.map(JacksonJsonHttpMessageConverter.class::cast).findFirst().orElseThrow();
		assertThat(mvcConverter.getMapper().isEnabled(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)).isFalse();
	}

	@Test
	void bindsBothPatientNamesWithoutNormalizingTheirWhitespaceOrCase() throws Exception {
		for (var request : readBoth(VALID_JSON)) {
			assertThat(validator.validate(request)).isEmpty();
			assertThat(request.patientFirstName()).isEqualTo(" Ana María ");
			assertThat(request.patientLastName()).isEqualTo(" Pérez ");
			assertThat(request.toString()).isEqualTo("ConfirmReservationRequest[redacted]");
		}
	}

	@ParameterizedTest
	@ValueSource(ints = {2, 100})
	void bothMappersAcceptBothNameLengthBoundaries(int length) throws Exception {
		String json = "{\"patientFirstName\":\"" + "a".repeat(length)
				+ "\",\"patientLastName\":\"" + "b".repeat(length) + "\"}";
		for (var request : readBoth(json)) {
			assertThat(validator.validate(request)).isEmpty();
			assertThat(request.patientFirstName()).hasSize(length);
			assertThat(request.patientLastName()).hasSize(length);
		}
	}

	@ParameterizedTest
	@ValueSource(strings = {"patientFirstName", "patientLastName"})
	void omittedAndExplicitlyNullNamesRemainInvalidInsteadOfReceivingDefaults(String field) throws Exception {
		var omitted = (ObjectNode) permissiveMapper.readTree(VALID_JSON);
		omitted.remove(field);
		for (String json : List.of(omitted.toString(), replace(field, "null"))) {
			for (var request : readBoth(json)) {
				assertThat(validator.validate(request)).singleElement().satisfies(violation ->
						assertThat(violation.getPropertyPath().toString()).isEqualTo(field));
			}
		}
	}

	@Test
	void anEmptyObjectHasTwoRequiredFieldViolations() throws Exception {
		for (var request : readBoth("{}")) {
			assertThat(validator.validate(request)).hasSize(2);
		}
	}

	@ParameterizedTest
	@ValueSource(strings = {"", " ", "\t\n", "A"})
	void blankOrTooShortStringsReachJakartaValidationUnchanged(String name) throws Exception {
		for (String field : List.of("patientFirstName", "patientLastName")) {
			String json = replace(field, permissiveMapper.writeValueAsString(name));
			for (var request : readBoth(json)) {
				assertThat(validator.validate(request)).isNotEmpty().allSatisfy(violation ->
						assertThat(violation.getPropertyPath().toString()).isEqualTo(field));
			}
		}
	}

	@Test
	void overlongInputProducesFixedValidationMessagesWithoutRejectedValues() throws Exception {
		String privateInput = "private-patient-input".repeat(6);
		for (String field : List.of("patientFirstName", "patientLastName")) {
			for (var request : readBoth(replace(field, permissiveMapper.writeValueAsString(privateInput)))) {
				assertThat(validator.validate(request)).singleElement().satisfies(violation -> {
					assertThat(violation.getPropertyPath().toString()).isEqualTo(field);
					assertThat(violation.getMessage()).doesNotContain(privateInput, "private-patient-input");
				});
			}
		}
	}

	@ParameterizedTest
	@ValueSource(strings = {"42", "42.0", "1e2", "true", "false", "{}", "[]", "[\"Ana\"]"})
	void rejectsNonStringValuesWithoutCoercingThemIntoPatientNames(String literal) {
		for (String field : List.of("patientFirstName", "patientLastName")) {
			assertInvalidJson(replace(field, literal));
		}
	}

	@ParameterizedTest
	@ValueSource(strings = {
			"\"id\":123", "\"userId\":999", "\"ownerUserId\":999", "\"owner\":{\"id\":999}",
			"\"externalPatientId\":\"user-999\"", "\"holdId\":\"private-extra-input\"",
			"\"reservationProcessId\":\"private-extra-input\"", "\"externalReservationProcessId\":\"private-extra-input\"",
			"\"groupId\":\"another-group\"", "\"technicalJwt\":\"private-extra-input\"", "\"version\":0",
			"\"professionalId\":202", "\"date\":\"2026-11-26\"", "\"startTime\":\"18:00\"",
			"\"status\":\"CONFIRMED\"", "\"expiresAt\":\"2099-01-01T00:00:00Z\"",
			"\"activated\":true", "\"authorities\":[\"ROLE_ADMIN\"]", "\"passwordHash\":\"private-extra-input\"",
			"\"createdBy\":\"admin\"", "\"createdDate\":\"2026-01-01T00:00:00Z\"",
			"\"lastModifiedBy\":\"admin\"", "\"lastModifiedDate\":\"2026-01-01T00:00:00Z\"",
			"\"firstName\":\"Other\"", "\"lastName\":\"Patient\"", "\"PATIENTFIRSTNAME\":\"Other\"",
			"\"unexpected\":null", "\"unexpected\":{\"nested\":\"private-extra-input\"}", "\"unexpected\":[1,2]"
	})
	void rejectsUnknownAndAdministrativeFieldsRegardlessOfGlobalMapperPermissiveness(String property) {
		String json = VALID_JSON.replace("}", "," + property + "}");
		assertThatThrownBy(() -> permissiveMapper.readValue(json, ConfirmReservationRequest.class))
				.hasRootCauseInstanceOf(IllegalArgumentException.class)
				.hasRootCauseMessage("Unknown reservation-confirmation property");
		assertThatThrownBy(() -> readWithMvc(json)).isInstanceOf(HttpMessageNotReadableException.class)
				.hasRootCauseInstanceOf(IllegalArgumentException.class)
				.hasRootCauseMessage("Unknown reservation-confirmation property");
	}

	@ParameterizedTest
	@ValueSource(strings = {"", "{broken", "[]", "42", "true", "\"not-a-request\""})
	void rejectsMalformedAndNonObjectJson(String json) {
		assertInvalidJson(json);
	}

	@ParameterizedTest
	@EnumSource(value = ReservationProcessStatus.class, names = {"CONFIRMATION_PENDING", "WAITING_FOR_PHONE"})
	void bothSerializersExposeExactlyTheThreeApprovedResponseFields(ReservationProcessStatus status) throws Exception {
		var expiry = Instant.parse("2026-11-19T22:30:00.123456789Z");
		var response = new ConfirmReservationResponse(123, status, expiry);
		var output = new MockHttpOutputMessage();
		mvcConverter.write(response, MediaType.APPLICATION_JSON, output);
		for (String serialized : List.of(permissiveMapper.writeValueAsString(response),
				output.getBodyAsString(StandardCharsets.UTF_8))) {
			var body = permissiveMapper.readTree(serialized);
			assertThat(body.size()).isEqualTo(3);
			assertThat(body.get("id").isIntegralNumber()).isTrue();
			assertThat(body.get("id").asLong()).isEqualTo(123);
			assertThat(body.get("status").asString()).isEqualTo(status.name());
			assertThat(body.get("expiresAt").isTextual()).isTrue();
			assertThat(body.get("expiresAt").asString()).isEqualTo(expiry.toString());
		}
	}

	private List<ConfirmReservationRequest> readBoth(String json) throws Exception {
		return List.of(permissiveMapper.readValue(json, ConfirmReservationRequest.class), readWithMvc(json));
	}

	private void assertInvalidJson(String json) {
		assertThatThrownBy(() -> permissiveMapper.readValue(json, ConfirmReservationRequest.class)).isInstanceOf(RuntimeException.class);
		assertThatThrownBy(() -> readWithMvc(json)).isInstanceOf(HttpMessageNotReadableException.class);
	}

	private String replace(String field, String literal) {
		var object = (ObjectNode) permissiveMapper.readTree(VALID_JSON);
		object.set(field, permissiveMapper.readTree(literal));
		return object.toString();
	}

	private ConfirmReservationRequest readWithMvc(String json) throws Exception {
		var input = new MockHttpInputMessage(json.getBytes(StandardCharsets.UTF_8));
		input.getHeaders().setContentType(MediaType.APPLICATION_JSON);
		return (ConfirmReservationRequest) mvcConverter.read(ConfirmReservationRequest.class, input);
	}
}
