package ar.edu.um.prog2.appointmentreservation.user.infrastructure.security;

import java.time.Clock;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.stream.Collectors;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import com.nimbusds.jose.jwk.source.ImmutableSecret;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import ar.edu.um.prog2.appointmentreservation.user.application.model.AuthenticationToken;

public final class NimbusFinalUserTokenIssuer {
	private final JwtEncoder encoder;
	private final FinalUserJwtProperties properties;
	private final Clock clock;

	public NimbusFinalUserTokenIssuer(FinalUserJwtProperties properties, Clock clock) {
		this.encoder = createEncoder(properties);
		this.properties = properties;
		this.clock = clock;
	}

	public AuthenticationToken issue(FinalUserPrincipal user, boolean rememberMe) {
		var issuedAt = clock.instant().truncatedTo(ChronoUnit.SECONDS);
		var ttl = rememberMe ? properties.getRememberMeTtl() : properties.getTtl();
		var claims = JwtClaimsSet.builder().subject(user.getUsername()).claim("userId", user.userId())
				// Use persisted principal roles, not Spring's Authentication factor markers.
				.claim("auth", user.getAuthorities().stream().map(GrantedAuthority::getAuthority)
						.sorted().collect(Collectors.joining(" ")))
				.issuedAt(issuedAt).expiresAt(issuedAt.plus(ttl))
				.issuer(FinalUserJwtProperties.ISSUER).audience(List.of(FinalUserJwtProperties.AUDIENCE)).build();
		var parameters = JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS512).build(), claims);
		return new AuthenticationToken(encoder.encode(parameters).getTokenValue());
	}

	private static JwtEncoder createEncoder(FinalUserJwtProperties properties) {
		validateDuration(properties.getTtl());
		validateDuration(properties.getRememberMeTtl());
		if (properties.getRememberMeTtl().compareTo(properties.getTtl()) < 0) {
			throw new IllegalStateException("Final-user JWT remember-me TTL must not be shorter than normal TTL");
		}
		return new NimbusJwtEncoder(new ImmutableSecret<>(signingKey(properties)));
	}

	// Shared validation for issuance and decoding; key material is never a Spring bean.
	static SecretKey signingKey(FinalUserJwtProperties properties) {
		String secret = properties.getBase64Secret();
		if (secret == null || secret.isBlank()) {
			throw new IllegalStateException("Final-user JWT signing key is required");
		}
		byte[] decoded;
		try {
			decoded = Base64.getDecoder().decode(secret);
		}
		catch (IllegalArgumentException failure) {
			throw new IllegalStateException("Final-user JWT signing key must be valid Base64");
		}
		try {
			if (decoded.length < 64) {
				throw new IllegalStateException("Final-user JWT signing key requires at least 64 decoded bytes");
			}
			return new SecretKeySpec(decoded, "HmacSHA512");
		}
		finally {
			Arrays.fill(decoded, (byte) 0);
		}
	}

	private static void validateDuration(Duration duration) {
		if (duration == null || duration.isNegative() || duration.isZero() || duration.getNano() != 0) {
			throw new IllegalStateException("Final-user JWT TTLs must be positive whole numbers of seconds");
		}
	}
}
