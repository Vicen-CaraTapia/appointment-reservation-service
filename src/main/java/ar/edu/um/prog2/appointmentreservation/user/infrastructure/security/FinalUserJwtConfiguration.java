package ar.edu.um.prog2.appointmentreservation.user.infrastructure.security;

import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import com.nimbusds.jose.jwk.source.ImmutableSecret;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(FinalUserJwtProperties.class)
public class FinalUserJwtConfiguration {

	@Bean
	public SecretKey finalUserSigningKey(FinalUserJwtProperties properties) {
		validateDuration(properties.getTtl());
		validateDuration(properties.getRememberMeTtl());
		if (properties.getRememberMeTtl().compareTo(properties.getTtl()) < 0) {
			throw new IllegalStateException("Final-user JWT remember-me TTL must not be shorter than normal TTL");
		}
		String secret = properties.getBase64Secret();
		if (secret == null || secret.isBlank()) {
			throw new IllegalStateException("Final-user JWT signing key is required");
		}
		byte[] decoded;
		try {
			decoded = Base64.getDecoder().decode(secret);
		}
		catch (IllegalArgumentException failure) {
			// Do not retain the decoder exception or echo the configured value.
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

	@Bean
	public JwtEncoder finalUserJwtEncoder(@Qualifier("finalUserSigningKey") SecretKey key) {
		return new NimbusJwtEncoder(new ImmutableSecret<>(key));
	}

	private static void validateDuration(Duration duration) {
		if (duration == null || duration.isNegative() || duration.isZero() || duration.getNano() != 0) {
			throw new IllegalStateException("Final-user JWT TTLs must be positive whole numbers of seconds");
		}
	}
}
