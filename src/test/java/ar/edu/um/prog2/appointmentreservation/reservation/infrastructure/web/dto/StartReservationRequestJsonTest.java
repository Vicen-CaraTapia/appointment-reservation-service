package ar.edu.um.prog2.appointmentreservation.reservation.infrastructure.web.dto;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.mock.http.MockHttpInputMessage;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import ar.edu.um.prog2.appointmentreservation.user.application.usecase.RegisterUserUseCase;
import ar.edu.um.prog2.appointmentreservation.user.infrastructure.security.FinalUserHttpSecurityConfiguration;
import ar.edu.um.prog2.appointmentreservation.user.infrastructure.web.UserRegistrationController;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// Reuses an existing MVC slice to exercise its actual converter without introducing the A6.4 endpoint yet.
@WebMvcTest(controllers = UserRegistrationController.class,
		excludeAutoConfiguration = UserDetailsServiceAutoConfiguration.class,
		properties = "spring.jackson.deserialization.fail-on-unknown-properties=false")
@Import(FinalUserHttpSecurityConfiguration.class)
class StartReservationRequestJsonTest {

	private static final String VALID_JSON = """
			{"professionalId":202,"date":"2026-11-19","startTime":"18:00"}
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
	void findsTheActualMvcConverterAndConfirmsUnknownPropertiesAreGloballyPermitted() {
		mvcConverter = handlerAdapter.getMessageConverters().stream()
				.filter(JacksonJsonHttpMessageConverter.class::isInstance)
				.map(JacksonJsonHttpMessageConverter.class::cast).findFirst().orElseThrow();
		assertThat(mvcConverter.getMapper().isEnabled(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)).isFalse();
	}

	@Test
	void bindsTheThreePublicSelectionFieldsWithBothMappers() throws Exception {
		for (var request : readBoth(VALID_JSON)) {
			assertThat(validator.validate(request)).isEmpty();
			assertThat(request.professionalId()).isEqualTo(202L);
			assertThat(request.date()).isEqualTo(LocalDate.of(2026, 11, 19));
			assertThat(request.startTime()).isEqualTo(LocalTime.of(18, 0));
			assertThat(request.toString()).isEqualTo("StartReservationRequest[redacted]");
		}
	}

	@ParameterizedTest
	@ValueSource(longs = {Long.MIN_VALUE, -1, 0, 202, Long.MAX_VALUE})
	void preservesTheEntireInt64NumberWithoutCoercion(long id) throws Exception {
		for (var request : readBoth(replace("professionalId", Long.toString(id)))) {
			assertThat(request.professionalId()).isEqualTo(id);
			assertThat(validator.validate(request)).isEmpty();
		}
	}

	@ParameterizedTest
	@ValueSource(strings = {"18:00", "18:00:00", "18:00:01.123456789", "23:59:59.999999999", "00:00"})
	void acceptsIsoLocalTimesAndPreservesTheirPrecisionForSubsequentAgendaValidation(String time) throws Exception {
		for (var request : readBoth(replace("startTime", "\"" + time + "\""))) {
			assertThat(request.startTime()).isEqualTo(LocalTime.parse(time));
		}
	}

	@Test
	void acceptsAValidLeapDay() throws Exception {
		for (var request : readBoth(replace("date", "\"2028-02-29\""))) {
			assertThat(request.date()).isEqualTo(LocalDate.of(2028, 2, 29));
		}
	}

	@ParameterizedTest
	@ValueSource(strings = {"professionalId", "date", "startTime"})
	void rejectsOmittedAndExplicitlyNullFieldsThroughJakartaValidation(String field) throws Exception {
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
	void anEmptyObjectDoesNotBecomeASelectionWithADefaultZeroId() throws Exception {
		for (var request : readBoth("{}")) {
			assertThat(request.professionalId()).isNull();
			assertThat(validator.validate(request)).hasSize(3);
		}
	}

	@ParameterizedTest
	@ValueSource(strings = {"202.0", "202.9", "2e2", "\"202\"", "\"\"", "true", "[202]", "{}",
			"9223372036854775808", "-9223372036854775809"})
	void rejectsWrongIdTypesFractionalNumbersAndOverflowsInsteadOfSelectingAnotherProfessional(String literal) {
		assertInvalidJson(replace("professionalId", literal));
	}

	@ParameterizedTest
	@ValueSource(strings = {"\"\"", "\" \"", "\"19/11/2026\"", "\"2026-02-29\"", "\"2026-13-01\"",
			"\"2026-11-31\"", "\"2026-11-19T00:00:00\"", "\"2026-11-19Z\"", "42", "true", "{}",
			"[2026,11,19]", "[\"2026-11-19\"]"})
	void rejectsInvalidCalendarDatesAndNonStringDateFormats(String literal) {
		assertInvalidJson(replace("date", literal));
	}

	@ParameterizedTest
	@ValueSource(strings = {"\"\"", "\" \"", "\"24:00\"", "\"18:60\"", "\"18:00:60\"", "\"18:0\"",
			"\"2026-11-19T18:00:00\"", "\"18:00:00Z\"", "\"18:00:00-03:00\"", "42", "true", "{}", "[18,0]", "[\"18:00\"]"})
	void rejectsInvalidTimesDateTimesOffsetsAndNonStringTimeFormats(String literal) {
		assertInvalidJson(replace("startTime", literal));
	}

	@ParameterizedTest
	@ValueSource(strings = {
			"\"id\":123", "\"userId\":999", "\"ownerUserId\":999", "\"owner\":{\"id\":999}",
			"\"login\":\"another-user\"", "\"externalPatientId\":\"user-999\"",
			"\"holdId\":\"private-extra-input\"", "\"reservationProcessId\":\"private-extra-input\"",
			"\"status\":\"CONFIRMED\"", "\"expiresAt\":\"2099-01-01T00:00:00Z\"",
			"\"activated\":true", "\"authorities\":[\"ROLE_ADMIN\"]", "\"passwordHash\":\"private-extra-input\"",
			"\"createdBy\":\"admin\"", "\"createdDate\":\"2026-01-01T00:00:00Z\"",
			"\"lastModifiedBy\":\"admin\"", "\"lastModifiedDate\":\"2026-01-01T00:00:00Z\"",
			"\"unexpected\":null", "\"unexpected\":{\"nested\":\"private-extra-input\"}", "\"PROFESSIONAL_ID\":202"
	})
	void rejectsAdministrativeOwnershipAndUnknownFieldsEvenWithPermissiveMappers(String property) {
		String json = VALID_JSON.replace("}", "," + property + "}");
		assertThatThrownBy(() -> permissiveMapper.readValue(json, StartReservationRequest.class))
				.hasRootCauseInstanceOf(IllegalArgumentException.class)
				.hasRootCauseMessage("Unknown reservation-start property");
		assertThatThrownBy(() -> readWithMvc(json)).isInstanceOf(HttpMessageNotReadableException.class)
				.hasRootCauseInstanceOf(IllegalArgumentException.class)
				.hasRootCauseMessage("Unknown reservation-start property");
	}

	@ParameterizedTest
	@ValueSource(strings = {"", "{broken", "[]", "42", "\"not-a-selection\""})
	void rejectsMalformedOrNonObjectJson(String json) {
		assertInvalidJson(json);
	}

	private List<StartReservationRequest> readBoth(String json) throws Exception {
		return List.of(permissiveMapper.readValue(json, StartReservationRequest.class), readWithMvc(json));
	}

	private void assertInvalidJson(String json) {
		assertThatThrownBy(() -> permissiveMapper.readValue(json, StartReservationRequest.class)).isInstanceOf(RuntimeException.class);
		assertThatThrownBy(() -> readWithMvc(json)).isInstanceOf(HttpMessageNotReadableException.class);
	}

	private String replace(String field, String literal) {
		var object = (ObjectNode) permissiveMapper.readTree(VALID_JSON);
		object.set(field, permissiveMapper.readTree(literal));
		return object.toString();
	}

	private StartReservationRequest readWithMvc(String json) throws Exception {
		var input = new MockHttpInputMessage(json.getBytes(StandardCharsets.UTF_8));
		input.getHeaders().setContentType(MediaType.APPLICATION_JSON);
		return (StartReservationRequest) mvcConverter.read(StartReservationRequest.class, input);
	}
}
