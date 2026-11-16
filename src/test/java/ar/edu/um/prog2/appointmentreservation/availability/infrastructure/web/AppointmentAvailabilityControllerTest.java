package ar.edu.um.prog2.appointmentreservation.availability.infrastructure.web;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
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

import ar.edu.um.prog2.appointmentreservation.availability.application.usecase.CalculateAppointmentAvailabilityUseCase;
import ar.edu.um.prog2.appointmentreservation.support.FinalUserJwtTestSupport;
import ar.edu.um.prog2.appointmentreservation.user.infrastructure.security.FinalUserHttpSecurityConfiguration;
import ar.edu.um.prog2.appointmentreservation.user.infrastructure.security.FinalUserJwtProperties;
import ar.edu.um.prog2.appointmentreservation.user.infrastructure.security.FinalUserPrincipal;
import ar.edu.um.prog2.appointmentreservation.user.infrastructure.security.FinalUserSecurityConfiguration;
import ar.edu.um.prog2.appointmentreservation.user.infrastructure.security.NimbusFinalUserTokenIssuer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = AppointmentAvailabilityController.class,
		excludeAutoConfiguration = UserDetailsServiceAutoConfiguration.class)
@Import({FinalUserHttpSecurityConfiguration.class, AppointmentAvailabilityControllerTest.JwtTestConfiguration.class})
@ExtendWith(OutputCaptureExtension.class)
class AppointmentAvailabilityControllerTest {

	private static final String PATH = "/api/appointment-availability";
	private static final LocalDate DATE = LocalDate.of(2026, 11, 17);
	private static final String SECRET = FinalUserJwtTestSupport.generateSecret();
	private static final String PRIVATE_INPUT = "test-only-private-query-value";

	@Autowired
	private MockMvc mvc;

	@MockitoBean
	private CalculateAppointmentAvailabilityUseCase availability;

	@Test
	void aRealFinalUserBearerTokenReturnsOnlyAvailabilityWithoutCachingOrSession(CapturedOutput output) throws Exception {
		when(availability.calculate(202, DATE)).thenReturn(List.of(LocalTime.of(11, 30), LocalTime.NOON,
				LocalTime.of(12, 30, 0, 123456789)));
		String token = validToken();
		String body = mvc.perform(get(PATH).param("professionalId", "202").param("date", DATE.toString())
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.professionalId").value(202))
				.andExpect(jsonPath("$.date").value("2026-11-17"))
				.andExpect(jsonPath("$.slots").value(org.hamcrest.Matchers.contains("11:30:00", "12:00:00", "12:30:00.123456789")))
				.andExpect(jsonPath("$.*").value(org.hamcrest.Matchers.hasSize(3)))
				.andExpect(header().string(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE))
				.andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
				.andExpect(header().doesNotExist(HttpHeaders.AUTHORIZATION))
				.andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE))
				.andExpect(result -> assertThat(result.getRequest().getSession(false)).isNull())
				.andReturn().getResponse().getContentAsString();
		assertThat(body).doesNotContain(token, "password", "authorities", "userId", "technicalJwt", "issuer");
		assertThat(output.getAll()).doesNotContain(token, SECRET);
		verify(availability).calculate(202, DATE);
		verifyNoMoreInteractions(availability);
	}

	@Test
	void emptyAvailabilityIsAValid200Response() throws Exception {
		when(availability.calculate(202, DATE)).thenReturn(List.of());
		mvc.perform(get(PATH).param("professionalId", "202").param("date", DATE.toString())
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + validToken()))
				.andExpect(status().isOk()).andExpect(jsonPath("$.slots").isEmpty())
				.andExpect(jsonPath("$.date").value(DATE.toString()));
		verify(availability).calculate(202, DATE);
	}

	@ParameterizedTest
	@ValueSource(strings = {"missing", "malformed", "wrong-key", "expired"})
	void invalidOrMissingUserAuthenticationNeverReachesTheCalculation(String scenario, CapturedOutput output) throws Exception {
		String token = switch (scenario) {
			case "wrong-key" -> token(FinalUserJwtTestSupport.generateSecret(), Clock.systemUTC());
			case "expired" -> token(SECRET, Clock.fixed(Instant.now().minus(Duration.ofHours(2)), ZoneOffset.UTC));
			case "malformed" -> "test-only-malformed-token";
			default -> "";
		};
		var request = get(PATH).param("professionalId", "202").param("date", DATE.toString());
		if (!token.isEmpty()) {
			request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
		}
		String body = mvc.perform(request).andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
				.andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"))
				.andReturn().getResponse().getContentAsString();
		if (!token.isEmpty()) {
			assertThat(body).doesNotContain(token);
			assertThat(output.getAll()).doesNotContain(token);
		}
		verifyNoInteractions(availability);
	}

	@Test
	void missingAuthenticationIsRejectedBeforeParameterValidation() throws Exception {
		mvc.perform(get(PATH)).andExpect(status().isUnauthorized());
		verifyNoInteractions(availability);
	}

	@ParameterizedTest
	@ValueSource(strings = {"professionalId", "date"})
	void bothQueryParametersAreRequired(String omitted) throws Exception {
		var request = get(PATH).header(HttpHeaders.AUTHORIZATION, "Bearer " + validToken());
		if (!omitted.equals("professionalId")) {
			request.param("professionalId", "202");
		}
		if (!omitted.equals("date")) {
			request.param("date", DATE.toString());
		}
		mvc.perform(request).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
		verifyNoInteractions(availability);
	}

	@ParameterizedTest
	@ValueSource(strings = {"", " ", "abc", "1.5", "9223372036854775808", "-9223372036854775809", PRIVATE_INPUT})
	void invalidProfessionalIdsProduceSafe400Responses(String id, CapturedOutput output) throws Exception {
		String body = mvc.perform(get(PATH).param("professionalId", id).param("date", DATE.toString())
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + validToken()))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
				.andExpect(header().string(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_PROBLEM_JSON_VALUE))
				.andReturn().getResponse().getContentAsString();
		assertThat(body).doesNotContain(PRIVATE_INPUT, "NumberFormatException", "stackTrace");
		assertThat(output.getAll()).doesNotContain(PRIVATE_INPUT);
		verifyNoInteractions(availability);
	}

	@ParameterizedTest
	@ValueSource(strings = {"", " ", "2026-02-30", "17/11/2026", "2026-11-17T11:30:00", "2026-11-17+03:00", PRIVATE_INPUT})
	void datesMustBeValidIsoLocalDates(String date, CapturedOutput output) throws Exception {
		String body = mvc.perform(get(PATH).param("professionalId", "202").param("date", date)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + validToken()))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
				.andReturn().getResponse().getContentAsString();
		assertThat(body).doesNotContain(PRIVATE_INPUT, "DateTimeParseException", "stackTrace");
		assertThat(output.getAll()).doesNotContain(PRIVATE_INPUT);
		verifyNoInteractions(availability);
	}

	@ParameterizedTest
	@ValueSource(longs = {Long.MIN_VALUE, -1, 0, Long.MAX_VALUE})
	void preservesTheSignedInt64ProfessionalIdWithoutInventingAPositiveIdRestriction(long id) throws Exception {
		when(availability.calculate(id, DATE)).thenReturn(List.of());
		mvc.perform(get(PATH).param("professionalId", Long.toString(id)).param("date", DATE.toString())
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + validToken()))
				.andExpect(status().isOk()).andExpect(jsonPath("$.professionalId").value(id));
		verify(availability).calculate(id, DATE);
	}

	@Test
	void doesNotIntroduceAPastDateRestriction() throws Exception {
		var past = LocalDate.of(2000, 1, 1);
		when(availability.calculate(202, past)).thenReturn(List.of());
		mvc.perform(get(PATH).param("professionalId", "202").param("date", past.toString())
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + validToken())).andExpect(status().isOk());
		verify(availability).calculate(202, past);
	}

	@Test
	void theRouteDoesNotAcceptReservationOrHoldCreationByPost() throws Exception {
		mvc.perform(post(PATH).header(HttpHeaders.AUTHORIZATION, "Bearer " + validToken()))
				.andExpect(status().isMethodNotAllowed());
		verifyNoInteractions(availability);
	}

	private static String validToken() {
		return token(SECRET, Clock.systemUTC());
	}

	private static String token(String secret, Clock clock) {
		var properties = new FinalUserJwtProperties();
		properties.setBase64Secret(secret);
		var user = new FinalUserPrincipal(42L, "test-user", "test-only-hash", true,
				List.of(new SimpleGrantedAuthority("ROLE_USER")));
		return new NimbusFinalUserTokenIssuer(properties, clock).issue(user, false).value();
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
