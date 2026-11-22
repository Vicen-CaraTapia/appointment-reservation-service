package ar.edu.um.prog2.appointmentreservation.reservation.infrastructure.security;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import ar.edu.um.prog2.appointmentreservation.reservation.application.exception.ReservationAuthenticationRequiredException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ExtendWith(OutputCaptureExtension.class)
class SpringCurrentReservationUserTest {

	private static final String TOKEN = "private-test-bearer-token";
	private final SpringCurrentReservationUser currentUser = new SpringCurrentReservationUser();

	@AfterEach
	void clearsThreadContextAndChecksLogs(CapturedOutput output) {
		SecurityContextHolder.clearContext();
		assertThat(output.getAll()).doesNotContain(TOKEN, "private-test-password");
	}

	@Test
	void obtainsTheOwnerFromVerifiedClaimsAndNormalizesLoginWithoutUsingAuthenticationName() {
		authenticate(Map.of("userId", 42L, "sub", "ALICE"));
		var result = currentUser.requireAuthenticated();
		assertThat(result.userId()).isEqualTo(42);
		assertThat(result.login()).isEqualTo("alice");
		assertThat(result.toString()).doesNotContain(TOKEN, "ALICE");
	}

	@Test
	void rejectsAnAbsentContextIdentity() {
		SecurityContextHolder.clearContext();
		assertRejected();
	}

	@Test
	void rejectsAnonymousAndPasswordAuthenticationEvenWhenMarkedAuthenticated() {
		SecurityContextHolder.getContext().setAuthentication(new AnonymousAuthenticationToken("anonymous-key",
				"anonymousUser", List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS"))));
		assertRejected();
		SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
				"alice", "private-test-password", List.of(new SimpleGrantedAuthority("ROLE_USER"))));
		assertRejected();
	}

	@Test
	void rejectsAnUnauthenticatedBearerToken() {
		authenticate(Map.of("userId", 42L, "sub", "alice"));
		SecurityContextHolder.getContext().getAuthentication().setAuthenticated(false);
		assertRejected();
	}

	@ParameterizedTest
	@ValueSource(longs = {1, 42, Long.MAX_VALUE})
	void preservesTheExactPositiveInt64Identity(long id) {
		authenticate(Map.of("userId", id, "sub", "alice"));
		assertThat(currentUser.requireAuthenticated().userId()).isEqualTo(id);
	}

	@Test
	void acceptsAnExactIntegralDecimalLikeTheExistingJwtValidator() {
		authenticate(Map.of("userId", new BigDecimal("42.0"), "sub", "alice"));
		assertThat(currentUser.requireAuthenticated().userId()).isEqualTo(42);
	}

	@Test
	void rejectsMissingNonNumericFractionalOverflowingAndNonpositiveIdsWithoutTruncation() {
		authenticate(Map.of("sub", "alice"));
		assertRejected();
		for (Object invalid : List.of("42", true, 0L, -1L, new BigDecimal("42.1"),
				new BigInteger("9223372036854775808"), Double.NaN, Double.POSITIVE_INFINITY)) {
			authenticate(Map.of("userId", invalid, "sub", "alice"));
			assertRejected();
		}
	}

	@Test
	void rejectsMissingNonTextualBlankOrUnpersistableLoginWithoutEchoingItsValue() {
		authenticate(Map.of("userId", 42L));
		assertRejected();
		for (Object invalid : List.of(42L, "", " ", "\t", "private-login-" + "x".repeat(50))) {
			authenticate(Map.of("userId", 42L, "sub", invalid));
			assertRejected();
		}
	}

	@Test
	void readsTheCurrentContextOnEveryCallInsteadOfCachingAnOwner() {
		authenticate(Map.of("userId", 42L, "sub", "alice"));
		assertThat(currentUser.requireAuthenticated().userId()).isEqualTo(42);
		authenticate(Map.of("userId", 43L, "sub", "bob"));
		assertThat(currentUser.requireAuthenticated().userId()).isEqualTo(43);
		SecurityContextHolder.clearContext();
		assertRejected();
	}

	private void assertRejected() {
		assertThatThrownBy(currentUser::requireAuthenticated)
				.isInstanceOf(ReservationAuthenticationRequiredException.class)
				.hasMessage("An authenticated final user is required to start a reservation process").hasNoCause();
	}

	private static void authenticate(Map<String, Object> values) {
		var claims = new HashMap<String, Object>(values);
		claims.put("testMarker", true);
		var jwt = Jwt.withTokenValue(TOKEN).header("alg", "HS512").claims(target -> target.putAll(claims)).build();
		var authentication = new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority("ROLE_USER")),
				"not-the-owner-login");
		var context = SecurityContextHolder.createEmptyContext();
		context.setAuthentication(authentication);
		SecurityContextHolder.setContext(context);
	}
}
