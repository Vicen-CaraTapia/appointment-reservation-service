package ar.edu.um.prog2.appointmentreservation.reservation.infrastructure.web;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.stream.Stream;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import ar.edu.um.prog2.appointmentreservation.availability.application.exception.InvalidAvailabilityScheduleException;
import ar.edu.um.prog2.appointmentreservation.availability.application.service.AvailableAppointmentSlotsCalculator;
import ar.edu.um.prog2.appointmentreservation.professional.application.exception.ProfessionalLookupException;
import ar.edu.um.prog2.appointmentreservation.professional.application.model.CurrentProfessional;
import ar.edu.um.prog2.appointmentreservation.professional.application.port.out.CurrentProfessionalSource;
import ar.edu.um.prog2.appointmentreservation.professional.application.usecase.LoadEnabledProfessionalUseCase;
import ar.edu.um.prog2.appointmentreservation.reservation.application.exception.HoldRequestException;
import ar.edu.um.prog2.appointmentreservation.reservation.application.exception.InvalidReservationSlotException;
import ar.edu.um.prog2.appointmentreservation.reservation.application.exception.ReservationAuthenticationRequiredException;
import ar.edu.um.prog2.appointmentreservation.reservation.application.exception.ReservationPersistenceException;
import ar.edu.um.prog2.appointmentreservation.reservation.application.model.AppointmentHold;
import ar.edu.um.prog2.appointmentreservation.reservation.application.model.AuthenticatedReservationUser;
import ar.edu.um.prog2.appointmentreservation.reservation.application.model.HeldReservationProcess;
import ar.edu.um.prog2.appointmentreservation.reservation.application.model.StartReservationCommand;
import ar.edu.um.prog2.appointmentreservation.reservation.application.port.out.AppointmentHoldRequester;
import ar.edu.um.prog2.appointmentreservation.reservation.application.port.out.ReservationProcessStore;
import ar.edu.um.prog2.appointmentreservation.reservation.application.service.ReservationSlotValidator;
import ar.edu.um.prog2.appointmentreservation.reservation.application.usecase.StartReservationProcessUseCase;
import ar.edu.um.prog2.appointmentreservation.reservation.infrastructure.security.SpringCurrentReservationUser;
import ar.edu.um.prog2.appointmentreservation.support.FinalUserJwtTestSupport;
import ar.edu.um.prog2.appointmentreservation.user.infrastructure.security.FinalUserHttpSecurityConfiguration;
import ar.edu.um.prog2.appointmentreservation.user.infrastructure.security.FinalUserJwtProperties;
import ar.edu.um.prog2.appointmentreservation.user.infrastructure.security.FinalUserPrincipal;
import ar.edu.um.prog2.appointmentreservation.user.infrastructure.security.FinalUserSecurityConfiguration;
import ar.edu.um.prog2.appointmentreservation.user.infrastructure.security.NimbusFinalUserTokenIssuer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = ReservationProcessController.class,
		excludeAutoConfiguration = UserDetailsServiceAutoConfiguration.class,
		properties = "spring.jackson.deserialization.fail-on-unknown-properties=false")
@Import({FinalUserHttpSecurityConfiguration.class, ReservationProcessControllerTest.JwtTestConfiguration.class})
@ExtendWith(OutputCaptureExtension.class)
class ReservationProcessControllerTest {

	private static final String PATH = "/api/reservation-processes";
	private static final String SECRET = FinalUserJwtTestSupport.generateSecret();
	private static final String PRIVATE_INPUT = "private-test-input-that-must-not-leak";
	private static final LocalDate DATE = LocalDate.of(2026, 11, 19);
	private static final StartReservationCommand SELECTION = new StartReservationCommand(202, DATE, LocalTime.of(18, 0));
	private static final AppointmentHold HOLD = new AppointmentHold("hold-private-external", "process-private-external",
			Instant.parse("2026-11-18T18:05:00.123456Z"));
	private static final HeldReservationProcess RESULT = new HeldReservationProcess(123, HOLD);
	private static final String VALID_JSON = """
			{"professionalId":202,"date":"2026-11-19","startTime":"18:00"}
			""";
	private final List<String> issuedTokens = new ArrayList<>();
	@Autowired private MockMvc mvc;
	@MockitoBean private StartReservationProcessUseCase startReservation;

	@AfterEach
	void outputNeverContainsCredentialsOrRejectedInput(CapturedOutput output) {
		assertThat(output.getAll()).doesNotContain(SECRET, PRIVATE_INPUT);
		for (String token : issuedTokens) {
			assertThat(output.getAll()).doesNotContain(token);
		}
	}

	@Test
	void aSignedFinalUserBearerCreatesAProcessAndReturnsOnlyTheSafePublicContract() throws Exception {
		when(startReservation.start(SELECTION)).thenReturn(RESULT);
		String token = validToken();
		String body = mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + token).content(VALID_JSON))
				.andExpect(status().isCreated())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
				.andExpect(jsonPath("$.id").value(123))
				.andExpect(jsonPath("$.status").value("HELD"))
				.andExpect(jsonPath("$.expiresAt").value(HOLD.expiresAt().toString()))
				.andExpect(jsonPath("$.*").value(org.hamcrest.Matchers.hasSize(3)))
				.andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
				.andExpect(header().doesNotExist(HttpHeaders.AUTHORIZATION))
				.andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE))
				.andExpect(result -> assertThat(result.getRequest().getSession(false)).isNull())
				.andReturn().getResponse().getContentAsString();
		assertThat(body).doesNotContain(token, SECRET, HOLD.holdId(), HOLD.reservationProcessId(),
				"userId", "owner", "password", "technicalJwt", "authorities", "createdBy");
		verify(startReservation).start(SELECTION);
		verifyNoMoreInteractions(startReservation);
	}

	@Test
	void callerSuppliedQueryOwnershipCannotReplaceTheOwnerFromTheValidatedJwt() throws Exception {
		var professionals = mock(CurrentProfessionalSource.class);
		var holds = mock(AppointmentHoldRequester.class);
		var store = mock(ReservationProcessStore.class);
		when(professionals.load(202)).thenReturn(new CurrentProfessional(202, "Martin", "Rivas", true,
				new CurrentProfessional.Category(101, "Cardiologia", true),
				List.of(new CurrentProfessional.WeeklySchedule(304, DATE.getDayOfWeek(), LocalTime.of(18, 0), LocalTime.of(21, 0), 30, true))));
		when(holds.createHold(SELECTION, "user-42")).thenReturn(HOLD);
		var owner = new AuthenticatedReservationUser(42, "alice");
		when(store.createHeld(owner, SELECTION, HOLD)).thenReturn(RESULT);
		var realUseCase = new StartReservationProcessUseCase(new SpringCurrentReservationUser(),
				new ReservationSlotValidator(new LoadEnabledProfessionalUseCase(professionals),
						new AvailableAppointmentSlotsCalculator()), holds, store);
		when(startReservation.start(any())).thenAnswer(call -> realUseCase.start(call.getArgument(0)));
		mvc.perform(post(PATH).param("userId", "999").param("ownerUserId", "999").param("externalPatientId", "user-999")
				.contentType(MediaType.APPLICATION_JSON).header(HttpHeaders.AUTHORIZATION, "Bearer " + validToken()).content(VALID_JSON))
				.andExpect(status().isCreated()).andExpect(jsonPath("$.id").value(123));
		verify(holds).createHold(SELECTION, "user-42");
		verify(store).createHeld(owner, SELECTION, HOLD);
		verifyNoMoreInteractions(holds, store);
	}

	@ParameterizedTest
	@ValueSource(strings = {"missing", "malformed", "wrong-key", "expired", "wrong-issuer", "wrong-audience", "technical-token"})
	void missingOrInvalidFinalUserAuthenticationNeverReachesTheUseCase(String scenario) throws Exception {
		String token = switch (scenario) {
			case "missing" -> "";
			case "malformed" -> PRIVATE_INPUT;
			case "wrong-key" -> token(FinalUserJwtTestSupport.generateSecret(), Clock.systemUTC());
			case "expired" -> token(SECRET, Clock.fixed(Instant.now().minus(Duration.ofHours(2)), ZoneOffset.UTC));
			case "wrong-issuer" -> alternateIdentityToken("other-service", FinalUserJwtProperties.AUDIENCE);
			case "wrong-audience" -> alternateIdentityToken(FinalUserJwtProperties.ISSUER, "other-app");
			default -> alternateIdentityToken("technical-service", "technical-api");
		};
		issuedTokens.add(token.isEmpty() ? "not-a-real-empty-token" : token);
		var request = post(PATH).contentType(MediaType.APPLICATION_JSON).content(VALID_JSON);
		if (!token.isEmpty()) {
			request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
		}
		String body = mvc.perform(request).andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
				.andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"))
				.andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
				.andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE))
				.andReturn().getResponse().getContentAsString();
		assertThat(body).doesNotContain(SECRET, PRIVATE_INPUT);
		if (!token.isEmpty()) { assertThat(body).doesNotContain(token); }
		verifyNoInteractions(startReservation);
	}

	@ParameterizedTest
	@ValueSource(strings = {"{}", "{\"date\":\"2026-11-19\",\"startTime\":\"18:00\"}",
			"{\"professionalId\":202,\"startTime\":\"18:00\"}", "{\"professionalId\":202,\"date\":\"2026-11-19\"}",
			"{\"professionalId\":null,\"date\":null,\"startTime\":null}"})
	void missingSelectionFieldsAre400WithoutCallingTheUseCase(String json) throws Exception {
		perform(json).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
				.andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"));
		verifyNoInteractions(startReservation);
	}

	@ParameterizedTest
	@ValueSource(strings = {"", "null", "{broken", "[]", "42",
			"{\"professionalId\":202.9,\"date\":\"2026-11-19\",\"startTime\":\"18:00\"}",
			"{\"professionalId\":9223372036854775808,\"date\":\"2026-11-19\",\"startTime\":\"18:00\"}",
			"{\"professionalId\":202,\"date\":\"2026-02-29\",\"startTime\":\"18:00\"}",
			"{\"professionalId\":202,\"date\":\"2026-11-19\",\"startTime\":\"18:00:00Z\"}"})
	void malformedOrUnsupportedJsonIs400WithoutCallingTheUseCase(String json) throws Exception {
		perform(json).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
		verifyNoInteractions(startReservation);
	}

	@ParameterizedTest
	@ValueSource(strings = {"\"userId\":999", "\"ownerUserId\":999", "\"externalPatientId\":\"user-999\"",
			"\"holdId\":\"external\"", "\"reservationProcessId\":\"external\"", "\"id\":999", "\"status\":\"CONFIRMED\"",
			"\"expiresAt\":\"2099-01-01T00:00:00Z\"", "\"authorities\":[\"ROLE_ADMIN\"]", "\"createdBy\":\"another-user\""})
	void unknownOrAdministrativeBodyFieldsAreRejectedEvenWhenGlobalJacksonIsPermissive(String property) throws Exception {
		String body = perform(VALID_JSON.replace("}", "," + property + "}"))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
				.andReturn().getResponse().getContentAsString();
		assertThat(body).doesNotContain("user-999", "ROLE_ADMIN", "another-user");
		verifyNoInteractions(startReservation);
	}

	@ParameterizedTest
	@EnumSource(ProfessionalLookupException.Reason.class)
	void professionalFailuresHaveConsistentPublicStatusAndCode(ProfessionalLookupException.Reason reason) throws Exception {
		when(startReservation.start(SELECTION)).thenThrow(new ProfessionalLookupException(reason));
		int expectedStatus = switch (reason) {
			case NOT_FOUND -> 404;
			case DISABLED -> 400;
			case CATALOG_TIMEOUT -> 504;
			case CATALOG_UNAVAILABLE -> 503;
			default -> 502;
		};
		String code = switch (reason) {
			case NOT_FOUND -> "PROFESSIONAL_NOT_FOUND";
			case DISABLED -> "PROFESSIONAL_DISABLED";
			case CATALOG_TIMEOUT -> "RESERVATION_DEPENDENCY_TIMEOUT";
			case CATALOG_UNAVAILABLE -> "RESERVATION_DEPENDENCY_UNAVAILABLE";
			default -> "RESERVATION_DEPENDENCY_ERROR";
		};
		assertFailure(expectedStatus, code);
	}

	@ParameterizedTest
	@EnumSource(HoldRequestException.Reason.class)
	void holdFailuresDistinguishBusinessConflictsFromDependencyAndUnknownOutcomes(HoldRequestException.Reason reason) throws Exception {
		when(startReservation.start(SELECTION)).thenThrow(new HoldRequestException(reason));
		int expectedStatus = switch (reason) {
			case PROFESSIONAL_NOT_FOUND -> 404;
			case PROFESSIONAL_DISABLED, INVALID_SLOT -> 400;
			case SLOT_ALREADY_HELD, SLOT_ALREADY_RESERVED -> 409;
			case TIMEOUT -> 504;
			case UNAVAILABLE -> 503;
			default -> 502;
		};
		String code = switch (reason) {
			case PROFESSIONAL_NOT_FOUND, PROFESSIONAL_DISABLED, INVALID_SLOT, SLOT_ALREADY_HELD, SLOT_ALREADY_RESERVED -> reason.name();
			case TIMEOUT, UNAVAILABLE, INVALID_RESPONSE -> "HOLD_OUTCOME_UNKNOWN";
			default -> "RESERVATION_DEPENDENCY_ERROR";
		};
		String body = assertFailure(expectedStatus, code);
		if (code.equals("HOLD_OUTCOME_UNKNOWN")) {
			assertThat(body).contains("outcome is unknown", "do not automatically repeat");
		}
	}

	@ParameterizedTest
	@MethodSource("localFailures")
	void localFailuresAreSanitizedAndDoNotExposeSensitiveDiagnostics(RuntimeException failure, int status, String code) throws Exception {
		when(startReservation.start(SELECTION)).thenThrow(failure);
		String body = assertFailure(status, code);
		if (failure instanceof ReservationPersistenceException) {
			assertThat(body).contains("remote hold may exist", "Do not automatically repeat");
		}
	}

	@Test
	void anInvalidBodyContainingSensitiveUnknownInputIsSanitized() throws Exception {
		String json = VALID_JSON.replace("}", ",\"passwordHash\":\"" + PRIVATE_INPUT + "\"}");
		String body = perform(json).andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString();
		assertThat(body).doesNotContain(PRIVATE_INPUT, "passwordHash", "IllegalArgumentException");
		verifyNoInteractions(startReservation);
	}

	@Test
	void otherMethodsAndUnsupportedMediaTypesCannotStartAProcess() throws Exception {
		mvc.perform(get(PATH).header(HttpHeaders.AUTHORIZATION, "Bearer " + validToken())).andExpect(status().isMethodNotAllowed());
		mvc.perform(post(PATH).contentType(MediaType.TEXT_PLAIN).header(HttpHeaders.AUTHORIZATION, "Bearer " + validToken())
				.content(VALID_JSON)).andExpect(status().isUnsupportedMediaType());
		verifyNoInteractions(startReservation);
	}

	static Stream<Arguments> localFailures() {
		return Stream.of(
				Arguments.of(new InvalidReservationSlotException(), 400, "INVALID_SLOT"),
				Arguments.of(new InvalidAvailabilityScheduleException(), 502, "RESERVATION_DEPENDENCY_ERROR"),
				Arguments.of(new ReservationAuthenticationRequiredException(), 401, "UNAUTHORIZED"),
				Arguments.of(new ReservationPersistenceException(), 500, "RESERVATION_PERSISTENCE_FAILED"),
				Arguments.of(new IllegalStateException(PRIVATE_INPUT, new RuntimeException(PRIVATE_INPUT)), 500, "RESERVATION_START_FAILED"));
	}

	private ResultActions perform(String json) throws Exception {
		return mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + validToken()).content(json));
	}

	private String assertFailure(int expectedStatus, String code) throws Exception {
		var result = perform(VALID_JSON).andExpect(status().is(expectedStatus))
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.status").value(expectedStatus)).andExpect(jsonPath("$.code").value(code))
				.andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
				.andExpect(header().doesNotExist(HttpHeaders.AUTHORIZATION))
				.andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE))
				.andExpect(header().doesNotExist(HttpHeaders.RETRY_AFTER));
		if (expectedStatus == 401) {
			result.andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"));
		} else {
			result.andExpect(header().doesNotExist(HttpHeaders.WWW_AUTHENTICATE));
		}
		String body = result.andReturn().getResponse().getContentAsString();
		assertThat(body).doesNotContain(SECRET, PRIVATE_INPUT, HOLD.holdId(), HOLD.reservationProcessId(),
				"technicalJwt", "passwordHash", "Exception", "stackTrace");
		verify(startReservation).start(SELECTION);
		verifyNoMoreInteractions(startReservation);
		return body;
	}

	private String validToken() {
		return token(SECRET, Clock.systemUTC());
	}

	private String token(String secret, Clock clock) {
		var properties = new FinalUserJwtProperties();
		properties.setBase64Secret(secret);
		String value = new NimbusFinalUserTokenIssuer(properties, clock).issue(new FinalUserPrincipal(42, "alice", "test-only-hash",
				true, List.of(new SimpleGrantedAuthority("ROLE_USER"))), false).value();
		issuedTokens.add(value);
		return value;
	}

	private String alternateIdentityToken(String issuer, String audience) throws Exception {
		var claims = new JWTClaimsSet.Builder().subject("alice").issuer(issuer).audience(audience)
				.claim("userId", 42L).claim("auth", "ROLE_USER").issueTime(new Date())
				.expirationTime(Date.from(Instant.now().plusSeconds(3600))).build();
		var jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS512), claims);
		jwt.sign(new MACSigner(Base64.getDecoder().decode(SECRET)));
		return jwt.serialize();
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class JwtTestConfiguration {
		@Bean
		JwtDecoder jwtDecoder() {
			var properties = new FinalUserJwtProperties();
			properties.setBase64Secret(SECRET);
			return new FinalUserSecurityConfiguration().finalUserJwtDecoder(properties);
		}
	}
}
