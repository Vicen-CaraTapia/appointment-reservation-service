package ar.edu.um.prog2.appointmentreservation.reservation.infrastructure.web;

import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.*;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import ar.edu.um.prog2.appointmentreservation.reservation.application.model.*;
import ar.edu.um.prog2.appointmentreservation.reservation.application.port.out.ReservationProcessStore;
import ar.edu.um.prog2.appointmentreservation.reservation.application.usecase.GetReservationProcessProgressUseCase;
import ar.edu.um.prog2.appointmentreservation.reservation.domain.model.ReservationProcessStatus;
import ar.edu.um.prog2.appointmentreservation.reservation.infrastructure.security.SpringCurrentReservationUser;
import ar.edu.um.prog2.appointmentreservation.support.FinalUserJwtTestSupport;
import ar.edu.um.prog2.appointmentreservation.user.infrastructure.security.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = ReservationProcessProgressController.class, excludeAutoConfiguration = UserDetailsServiceAutoConfiguration.class)
@Import({FinalUserHttpSecurityConfiguration.class, ReservationProcessProgressControllerTest.Settings.class})
class ReservationProcessProgressControllerTest {
	private static final String SECRET = FinalUserJwtTestSupport.generateSecret();
	private static final Instant NOW = Instant.parse("2030-01-01T12:00:00Z");
	@Autowired private MockMvc mvc;
	@MockitoBean private ReservationProcessStore store;

	@Test
	void signedBearerResolvesTheOwnerAndReturnsOnlyMinimalUncacheableProgress() throws Exception {
		when(store.findOwnedProgress(9, new AuthenticatedReservationUser(42, "alice")))
				.thenReturn(Optional.of(new StoredReservationProgress(9, ReservationProcessStatus.WAITING_FOR_PHONE, NOW.plusSeconds(30), true, null)));
		var result = mvc.perform(get("/api/reservation-processes/9").header("Authorization", "Bearer " + token(42, SECRET, Clock.systemUTC())))
				.andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
				.andExpect(header().doesNotExist("Set-Cookie")).andExpect(jsonPath("$.id").value(9))
				.andExpect(jsonPath("$.status").value("WAITING_FOR_PHONE"))
				.andExpect(jsonPath("$.requiredAction").value("SUBMIT_PHONE"))
				.andExpect(jsonPath("$.expiresAt").value("2030-01-01T12:00:30Z")).andReturn();
		var json = tools.jackson.databind.json.JsonMapper.builder().build().readTree(result.getResponse().getContentAsString());
		assertThat(json.size()).isEqualTo(5);
		verify(store).findOwnedProgress(9, new AuthenticatedReservationUser(42, "alice"));
		verifyNoMoreInteractions(store);
	}
	@Test
	void foreignAndMissingProcessesHaveIdentical404Responses() throws Exception {
		when(store.findOwnedProgress(eq(9L), any())).thenReturn(Optional.empty());
		String first = readMissing(42); String second = readMissing(43);
		assertThat(first).isEqualTo(second);
		verify(store).findOwnedProgress(9, new AuthenticatedReservationUser(43, "alice"));
	}
	@ParameterizedTest
	@ValueSource(strings = {"missing", "wrong-key", "expired", "malformed"})
	void invalidAuthenticationCannotReadStorage(String kind) throws Exception {
		var request = get("/api/reservation-processes/9");
		if (!kind.equals("missing")) {
			String value = switch (kind) {
				case "wrong-key" -> token(42, FinalUserJwtTestSupport.generateSecret(), Clock.systemUTC());
				case "expired" -> token(42, SECRET, Clock.fixed(Instant.now().minusSeconds(7200), ZoneOffset.UTC));
				default -> "invalid";
			};
			request.header("Authorization", "Bearer " + value);
		}
		mvc.perform(request).andExpect(status().isUnauthorized()).andExpect(header().string("Cache-Control", "no-store"));
		verifyNoInteractions(store);
	}
	@ParameterizedTest
	@ValueSource(strings = {"0", "-1", "abc", "9223372036854775808"})
	void invalidIdsAreRejectedBeforeStorage(String id) throws Exception {
		mvc.perform(get("/api/reservation-processes/" + id).header("Authorization", "Bearer " + token(42, SECRET, Clock.systemUTC())))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
				.andExpect(header().string("Cache-Control", "no-store"));
		verifyNoInteractions(store);
	}
	@Test
	void storageDiagnosticsAreNeverReturned() throws Exception {
		when(store.findOwnedProgress(anyLong(), any())).thenThrow(new IllegalStateException("private-db-diagnostic"));
		var result = mvc.perform(get("/api/reservation-processes/9").header("Authorization", "Bearer " + token(42, SECRET, Clock.systemUTC())))
				.andExpect(status().isInternalServerError()).andExpect(jsonPath("$.code").value("RESERVATION_PERSISTENCE_FAILED"))
				.andExpect(content().contentTypeCompatibleWith("application/problem+json")).andReturn();
		assertThat(result.getResponse().getContentAsString()).doesNotContain("private-db-diagnostic", SECRET);
	}
	private String readMissing(long owner) throws Exception {
		return mvc.perform(get("/api/reservation-processes/9").header("Authorization", "Bearer " + token(owner, SECRET, Clock.systemUTC())))
				.andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("RESERVATION_PROCESS_NOT_FOUND"))
				.andExpect(header().string("Cache-Control", "no-store")).andReturn().getResponse().getContentAsString();
	}
	private static String token(long owner, String secret, Clock clock) {
		var properties = new FinalUserJwtProperties(); properties.setBase64Secret(secret);
		return new NimbusFinalUserTokenIssuer(properties, clock).issue(new FinalUserPrincipal(owner, "alice", "test-hash", true,
				List.of(new SimpleGrantedAuthority("ROLE_USER"))), false).value();
	}
	@TestConfiguration(proxyBeanMethods = false)
	static class Settings {
		@Bean JwtDecoder decoder() {
			var properties = new FinalUserJwtProperties(); properties.setBase64Secret(SECRET);
			return new FinalUserSecurityConfiguration().finalUserJwtDecoder(properties);
		}
		@Bean GetReservationProcessProgressUseCase progress(ReservationProcessStore store) {
			return new GetReservationProcessProgressUseCase(new SpringCurrentReservationUser(), store, Clock.fixed(NOW, ZoneOffset.UTC));
		}
	}
}
