package ar.edu.um.prog2.appointmentreservation.user.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Set;
import java.util.HashSet;
import java.util.Base64;
import java.util.List;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import ar.edu.um.prog2.appointmentreservation.support.FinalUserJwtTestSupport;

class NimbusFinalUserTokenIssuerTest {
	private final Instant now = Instant.parse("2026-11-01T12:00:00.123Z");
	private final Clock clock = Clock.fixed(now, ZoneOffset.UTC);
	private final FinalUserJwtProperties properties = new FinalUserJwtProperties();
	private final FinalUserPrincipal user = new FinalUserPrincipal(42, "alice", "test-only-hash", true,
			List.of(new SimpleGrantedAuthority("ROLE_USER"), new SimpleGrantedAuthority("ROLE_ADMIN")));

	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	void signsOnlyApprovedClaimsWithTheSelectedLifetime(boolean rememberMe) {
		SecretKey key = key();
		String token = issuer().issue(user, rememberMe).value();
		var decoded = decoder(key, clock).decode(token);
		assertThat(decoded.getHeaders()).containsEntry("alg", "HS512");
		assertThat(decoded.getClaims()).containsOnlyKeys("sub", "userId", "auth", "iat", "exp", "iss", "aud");
		assertThat(decoded.getSubject()).isEqualTo("alice");
		assertThat(((Number) decoded.getClaim("userId")).longValue()).isEqualTo(42);
		assertThat(decoded.getClaimAsString("auth")).isEqualTo("ROLE_ADMIN ROLE_USER");
		assertThat(decoded.getClaimAsString("iss")).isEqualTo(FinalUserJwtProperties.ISSUER);
		assertThat(decoded.getAudience()).containsExactly(FinalUserJwtProperties.AUDIENCE);
		assertThat(decoded.getIssuedAt()).isEqualTo(now.truncatedTo(ChronoUnit.SECONDS));
		assertThat(Duration.between(decoded.getIssuedAt(), decoded.getExpiresAt()))
				.isEqualTo(rememberMe ? Duration.ofDays(7) : Duration.ofHours(1));
		assertThat(decoded.getClaims().toString()).doesNotContain(properties.getBase64Secret(), "password", "passwordHash");
	}

	@Test
	void respectsExternallyConfiguredDurations() {
		properties.setTtl(Duration.ofMinutes(15));
		properties.setRememberMeTtl(Duration.ofHours(3));
		SecretKey key = key();
		var normal = decoder(key, clock).decode(issuer().issue(user, false).value());
		var remembered = decoder(key, clock).decode(issuer().issue(user, true).value());
		assertThat(Duration.between(normal.getIssuedAt(), normal.getExpiresAt())).isEqualTo(Duration.ofMinutes(15));
		assertThat(Duration.between(remembered.getIssuedAt(), remembered.getExpiresAt())).isEqualTo(Duration.ofHours(3));
	}

	@Test
	void signatureIsInvalidWithADifferentKey() {
		key();
		String token = issuer().issue(user, false).value();
		SecretKey other = key();
		assertThatThrownBy(() -> decoder(other, clock).decode(token)).isInstanceOf(BadJwtException.class);
	}

	@Test
	void tokenIsRejectedAfterItsExpiration() {
		SecretKey key = key();
		String token = issuer().issue(user, false).value();
		Clock later = Clock.fixed(now.plus(Duration.ofHours(1)).plusSeconds(1), ZoneOffset.UTC);
		assertThatThrownBy(() -> decoder(key, later).decode(token)).isInstanceOf(BadJwtException.class);
	}

	private SecretKey key() {
		properties.setBase64Secret(FinalUserJwtTestSupport.generateSecret());
		return new SecretKeySpec(Base64.getDecoder().decode(properties.getBase64Secret()), "HmacSHA512");
	}

	private NimbusFinalUserTokenIssuer issuer() {
		return new NimbusFinalUserTokenIssuer(properties, clock);
	}

	@Test
	void verifiedPrincipalAuthoritiesCannotBeMutatedAfterConstruction() {
		var roles = new HashSet<>(Set.of(new SimpleGrantedAuthority("ROLE_USER")));
		var principal = new FinalUserPrincipal(42, "alice", "test-only-hash", true, roles);
		roles.add(new SimpleGrantedAuthority("ROLE_ADMIN"));
		assertThat(principal.getAuthorities()).extracting("authority").containsExactly("ROLE_USER");
		assertThatThrownBy(() -> principal.getAuthorities().clear()).isInstanceOf(UnsupportedOperationException.class);
	}

	private static NimbusJwtDecoder decoder(SecretKey key, Clock clock) {
		var decoder = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS512).build();
		var timestamps = new JwtTimestampValidator(Duration.ZERO);
		timestamps.setClock(clock);
		decoder.setJwtValidator(timestamps);
		return decoder;
	}
}
