package ar.edu.um.prog2.appointmentreservation.reservation.infrastructure.web;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import ar.edu.um.prog2.appointmentreservation.reservation.application.exception.OwnedReservationQueryException;
import ar.edu.um.prog2.appointmentreservation.reservation.application.exception.ReservationListingException;
import ar.edu.um.prog2.appointmentreservation.reservation.application.model.OwnedReservationPage;
import ar.edu.um.prog2.appointmentreservation.reservation.application.model.OwnedReservationQuery;
import ar.edu.um.prog2.appointmentreservation.reservation.application.model.TechnicalReservation;
import ar.edu.um.prog2.appointmentreservation.reservation.application.usecase.ListOwnedReservationsUseCase;
import ar.edu.um.prog2.appointmentreservation.reservation.domain.model.ReservationProcessStatus;
import ar.edu.um.prog2.appointmentreservation.support.FinalUserJwtTestSupport;
import ar.edu.um.prog2.appointmentreservation.user.infrastructure.security.FinalUserHttpSecurityConfiguration;
import ar.edu.um.prog2.appointmentreservation.user.infrastructure.security.FinalUserJwtProperties;
import ar.edu.um.prog2.appointmentreservation.user.infrastructure.security.FinalUserPrincipal;
import ar.edu.um.prog2.appointmentreservation.user.infrastructure.security.FinalUserSecurityConfiguration;
import ar.edu.um.prog2.appointmentreservation.user.infrastructure.security.NimbusFinalUserTokenIssuer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = OwnedReservationsController.class,
		excludeAutoConfiguration = UserDetailsServiceAutoConfiguration.class)
@Import({FinalUserHttpSecurityConfiguration.class, OwnedReservationsControllerTest.JwtConfiguration.class})
class OwnedReservationsControllerTest {
	private static final String SECRET = FinalUserJwtTestSupport.generateSecret();
	private static final LocalDate FROM = LocalDate.of(2030, 1, 1);
	private static final LocalDate TO = LocalDate.of(2030, 1, 31);
	private static final Instant VERIFIED = Instant.parse("2030-01-01T12:00:00Z");

	@Autowired private MockMvc mvc;
	@MockitoBean private ListOwnedReservationsUseCase reservations;

	@Test
	void signedFinalUserGetsOnlyTheOwnedSafeReservationPageAndFilters() throws Exception {
		var reservation = new TechnicalReservation(8251, "external-process-secret", "user-42", FROM,
				LocalTime.of(9, 30), LocalTime.of(10, 0), 201, "Martin", "Rivas", "Alice", "Example",
				"+5492615551234", TechnicalReservation.Status.CONFIRMED, VERIFIED, VERIFIED, null, null);
		when(reservations.list(new OwnedReservationQuery(TechnicalReservation.Status.CONFIRMED, FROM, TO, 1, 5)))
				.thenReturn(new OwnedReservationPage(List.of(new OwnedReservationPage.Item(12, reservation,
						ReservationProcessStatus.CONFIRMED, VERIFIED)), 6, 1, 5));

		var result = mvc.perform(get("/api/reservations").param("reservationStatus", "CONFIRMED")
				.param("from", FROM.toString()).param("to", TO.toString()).param("page", "1").param("size", "5")
				.param("userId", "999").header(HttpHeaders.AUTHORIZATION, "Bearer " + token(42)))
				.andExpect(status().isOk()).andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
				.andExpect(content().contentTypeCompatibleWith("application/json"))
				.andExpect(jsonPath("$.items[0].id").value(12))
				.andExpect(jsonPath("$.items[0].professionalId").value(201))
				.andExpect(jsonPath("$.items[0].processStatus").value("CONFIRMED"))
				.andExpect(jsonPath("$.items[0].reservationStatus").value("CONFIRMED"))
				.andExpect(jsonPath("$.total").value(6)).andExpect(jsonPath("$.page").value(1))
				.andExpect(jsonPath("$.size").value(5)).andReturn();

		var body = result.getResponse().getContentAsString();
		assertThat(body).doesNotContain("external-process-secret", "user-42", "+5492615551234",
				"patientPhone", "externalPatientId", "reservationProcessId", "holdId", "ownerUserId");
		verify(reservations).list(new OwnedReservationQuery(TechnicalReservation.Status.CONFIRMED, FROM, TO, 1, 5));
	}

	@ParameterizedTest
	@ValueSource(strings = {"missing", "malformed", "wrong-key", "expired"})
	void missingOrInvalidBearerIsRejectedBeforeListing(String authorization) throws Exception {
		var request = get("/api/reservations");
		if (!authorization.equals("missing")) {
			String bearer = switch (authorization) {
				case "malformed" -> "not-a-jwt";
				case "wrong-key" -> token(42, FinalUserJwtTestSupport.generateSecret());
				default -> new NimbusFinalUserTokenIssuer(properties(),
						Clock.fixed(Instant.now().minusSeconds(7200), ZoneOffset.UTC))
						.issue(principal(42), false).value();
			};
			request.header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer);
		}
		mvc.perform(request).andExpect(status().isUnauthorized())
				.andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"));
		verifyNoInteractions(reservations);
	}

	@ParameterizedTest
	@ValueSource(strings = {"reservationStatus=UNKNOWN", "page=-1", "size=0", "size=101",
			"from=2030-02-01&to=2030-01-01", "from=not-a-date"})
	void invalidFiltersAreRejectedBeforeListing(String query) throws Exception {
		var request = get("/api/reservations").header(HttpHeaders.AUTHORIZATION, "Bearer " + token(42));
		for (String part : query.split("&")) {
			var pair = part.split("=", 2);
			request.param(pair[0], pair[1]);
		}
		mvc.perform(request).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
				.andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"));
		verifyNoInteractions(reservations);
	}

	@Test
	void incompleteExternalListingIsReturnedAsSafeUnavailableProblem() throws Exception {
		when(reservations.list(any())).thenThrow(new ReservationListingException(ReservationListingException.Reason.TIMEOUT));
		var response = mvc.perform(get("/api/reservations").header(HttpHeaders.AUTHORIZATION, "Bearer " + token(42)))
				.andExpect(status().isServiceUnavailable())
				.andExpect(content().contentTypeCompatibleWith("application/problem+json"))
				.andExpect(jsonPath("$.code").value("RESERVATION_LIST_UNAVAILABLE"))
				.andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store")).andReturn();
		assertThat(response.getResponse().getContentAsString()).doesNotContain("token", SECRET, "diagnostic");
	}

	@Test
	void inconsistentLocalOwnershipIsReportedAsConflictWithoutDetails() throws Exception {
		when(reservations.list(any())).thenThrow(new OwnedReservationQueryException(
				OwnedReservationQueryException.Reason.CONFLICTING_ASSOCIATION));
		mvc.perform(get("/api/reservations").header(HttpHeaders.AUTHORIZATION, "Bearer " + token(42)))
				.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("RESERVATION_STATE_CONFLICT"))
				.andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"));
	}

	private static String token(long userId) {
		return token(userId, SECRET);
	}

	private static String token(long userId, String secret) {
		return new NimbusFinalUserTokenIssuer(properties(secret), Clock.systemUTC()).issue(principal(userId), false).value();
	}

	private static FinalUserJwtProperties properties() {
		return properties(SECRET);
	}

	private static FinalUserJwtProperties properties(String secret) {
		var properties = new FinalUserJwtProperties();
		properties.setBase64Secret(secret);
		return properties;
	}

	private static FinalUserPrincipal principal(long userId) {
		return new FinalUserPrincipal(userId, "alice", "test-hash", true,
				List.of(new SimpleGrantedAuthority("ROLE_USER")));
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class JwtConfiguration {
		@Bean JwtDecoder decoder() {
			return new FinalUserSecurityConfiguration().finalUserJwtDecoder(properties());
		}
	}
}
