package ar.edu.um.prog2.appointmentreservation.user.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSObject;
import com.nimbusds.jose.Payload;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.util.JSONObjectUtils;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

import ar.edu.um.prog2.appointmentreservation.support.FinalUserJwtTestSupport;

@ExtendWith(OutputCaptureExtension.class)
class FinalUserJwtDecoderTest {

	private final FinalUserJwtProperties properties = properties();
	private final JwtDecoder decoder = new FinalUserSecurityConfiguration().finalUserJwtDecoder(properties);

	@Test
	void acceptsARealHs512TokenWithTheFinalUserContract() throws Exception {
		var jwt = decoder.decode(sign(validClaims()));
		assertThat(jwt.getSubject()).isEqualTo("alice");
		assertThat(jwt.getClaimAsString("iss")).isEqualTo(FinalUserJwtProperties.ISSUER);
		assertThat(jwt.getAudience()).containsExactly(FinalUserJwtProperties.AUDIENCE);
		assertThat(((Number) jwt.getClaim("userId")).longValue()).isEqualTo(42);
		assertThat(jwt.getClaimAsString("auth")).isEqualTo("ROLE_USER");
		assertThat(jwt.getHeaders()).containsEntry("alg", "HS512");
	}

	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	void acceptsTokensFromTheExistingIssuerWithBothLifetimes(boolean rememberMe) {
		var issuer = new FinalUserSecurityConfiguration().finalUserTokenIssuer(properties);
		var principal = new FinalUserPrincipal(42, "alice", "test-only-hash", true,
				List.of(new SimpleGrantedAuthority("ROLE_USER")));
		assertThat(decoder.decode(issuer.issue(principal, rememberMe).value()).getSubject()).isEqualTo("alice");
	}

	@Test
	void rejectsADifferentSigningKey() throws Exception {
		String token = sign(validClaims(), JWSAlgorithm.HS512, FinalUserJwtTestSupport.generateSecret());
		rejected(token);
	}

	@ParameterizedTest
	@ValueSource(strings = {"HS256", "HS384"})
	void rejectsOtherMacAlgorithmsEvenWithTheCorrectKey(String algorithm) throws Exception {
		rejected(sign(validClaims(), JWSAlgorithm.parse(algorithm), properties.getBase64Secret()));
	}

	@Test
	void rejectsATamperedSignature() throws Exception {
		String token = sign(validClaims());
		int start = token.lastIndexOf('.') + 1;
		char changed = token.charAt(start) == 'A' ? 'B' : 'A';
		rejected(token.substring(0, start) + changed + token.substring(start + 1));
	}

	@Test
	void rejectsUnsignedTokens() {
		String header = Base64.getUrlEncoder().withoutPadding()
				.encodeToString("{\"alg\":\"none\"}".getBytes(StandardCharsets.UTF_8));
		String payload = Base64.getUrlEncoder().withoutPadding().encodeToString(JSONObjectUtils.toJSONString(validClaims())
				.getBytes(StandardCharsets.UTF_8));
		rejected(header + "." + payload + ".");
	}

	@Test
	void rejectsExpiredTokensWithoutAdditionalClockLeeway() throws Exception {
		var claims = validClaims();
		claims.put("exp", Instant.now().minusSeconds(5).getEpochSecond());
		rejected(sign(claims));
	}

	@Test
	void rejectsFutureNotBefore() throws Exception {
		var claims = validClaims();
		claims.put("nbf", Instant.now().plusSeconds(300).getEpochSecond());
		rejected(sign(claims));
	}

	@Test
	void acceptsPastNotBeforeAndMultiplePersistedAuthorities() throws Exception {
		var claims = validClaims();
		claims.put("nbf", Instant.now().minusSeconds(30).getEpochSecond());
		claims.put("auth", "ROLE_ADMIN ROLE_USER");
		assertThat(decoder.decode(sign(claims)).getClaimAsString("auth")).isEqualTo("ROLE_ADMIN ROLE_USER");
	}

	@Test
	void acceptsTheStandardSingleAudienceStringRepresentation() throws Exception {
		var claims = validClaims();
		claims.put("aud", FinalUserJwtProperties.AUDIENCE);
		assertThat(decoder.decode(sign(claims)).getAudience()).containsExactly(FinalUserJwtProperties.AUDIENCE);
	}

	@Test
	void invalidClaimsDoNotExposeInputValuesInErrorsOrLogs(CapturedOutput output) throws Exception {
		var claims = validClaims();
		String rejectedValue = "private-test-only-claim";
		claims.put("auth", rejectedValue);
		String token = sign(claims);
		assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(JwtException.class)
				.hasMessageNotContaining(rejectedValue).hasMessageNotContaining(token)
				.hasMessageNotContaining(properties.getBase64Secret());
		assertThat(output.getAll()).doesNotContain(rejectedValue, token, properties.getBase64Secret());
	}

	@ParameterizedTest
	@ValueSource(strings = {"iss", "aud", "sub", "userId", "auth", "exp"})
	void rejectsMissingRequiredClaims(String name) throws Exception {
		var claims = validClaims();
		claims.remove(name);
		rejected(sign(claims));
	}

	@ParameterizedTest
	@MethodSource("malformedClaims")
	void rejectsMalformedOrIncorrectClaims(String name, Object value) throws Exception {
		var claims = validClaims();
		claims.put(name, value);
		rejected(sign(claims));
	}

	static Stream<Arguments> malformedClaims() {
		return Stream.of(
				invalidValues("iss", null, "", "catedra-test-only", "appointment-reservation-service/", 42),
				invalidValues("aud", null, List.of(), List.of("technical-test-only"), List.of(42), 42),
				invalidValues("sub", null, "", " \t\n", 42, true, List.of("alice"), Map.of("login", "alice")),
				invalidValues("userId", null, "42", 0, -1, 1.5, true, List.of(42), Map.of("id", 42),
						new java.math.BigInteger("9223372036854775808")),
				invalidValues("auth", null, "", " ", "USER", "role_user", "ROLE_", "ROLE_USER,ROLE_ADMIN",
						" ROLE_USER", "ROLE_USER ", "ROLE_USER  ROLE_ADMIN", "ROLE_USER\tROLE_ADMIN", List.of("ROLE_USER"), 42),
				invalidValues("exp", null, "not-a-timestamp", "9999999999", Map.of("seconds", 42)),
				invalidValues("nbf", null, "not-a-timestamp", "1", List.of(42)))
				.flatMap(stream -> stream);
	}

	@Test
	void rejectsATechnicalIdentityEvenIfSignedWithTheTestKey() throws Exception {
		var claims = validClaims();
		claims.put("iss", "catedra-test-only");
		claims.put("aud", List.of("technical-test-only"));
		claims.put("auth", "ROLE_STUDENT_CLIENT");
		claims.remove("userId");
		rejected(sign(claims));
	}

	private static Stream<Arguments> invalidValues(String name, Object... values) {
		return Stream.of(values).map(value -> Arguments.of(name, value));
	}

	private Map<String, Object> validClaims() {
		var now = Instant.now();
		return new HashMap<>(Map.of("iss", FinalUserJwtProperties.ISSUER,
				"aud", List.of(FinalUserJwtProperties.AUDIENCE), "sub", "alice", "userId", 42,
				"auth", "ROLE_USER", "iat", now.minusSeconds(60).getEpochSecond(),
				"exp", now.plusSeconds(3600).getEpochSecond()));
	}

	private String sign(Map<String, Object> claims) throws Exception {
		return sign(claims, JWSAlgorithm.HS512, properties.getBase64Secret());
	}

	private static String sign(Map<String, Object> claims, JWSAlgorithm algorithm, String secret) throws Exception {
		// Raw payloads allow testing malformed claim types without builder coercion.
		var jwt = new JWSObject(new JWSHeader(algorithm), new Payload(JSONObjectUtils.toJSONString(claims)));
		jwt.sign(new MACSigner(Base64.getDecoder().decode(secret)));
		return jwt.serialize();
	}

	private void rejected(String token) {
		assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(JwtException.class)
				.hasMessageNotContaining(token).hasMessageNotContaining(properties.getBase64Secret());
	}

	private static FinalUserJwtProperties properties() {
		var properties = new FinalUserJwtProperties();
		properties.setBase64Secret(FinalUserJwtTestSupport.generateSecret());
		return properties;
	}
}
