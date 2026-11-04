package ar.edu.um.prog2.appointmentreservation.user.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.stream.Stream;

import javax.crypto.SecretKey;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import ar.edu.um.prog2.appointmentreservation.support.FinalUserJwtTestSupport;

@ExtendWith(OutputCaptureExtension.class)
class FinalUserJwtConfigurationTest {

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
			.withUserConfiguration(FinalUserJwtConfiguration.class);

	@Test
	void bindsDefaultsAndCreatesAnHs512SigningKeyWithoutLoggingIt(CapturedOutput output) {
		String secret = FinalUserJwtTestSupport.generateSecret();
		withSecret(secret).run(context -> {
			assertThat(context).hasNotFailed();
			FinalUserJwtProperties properties = context.getBean(FinalUserJwtProperties.class);
			assertThat(properties.getTtl()).isEqualTo(Duration.ofHours(1));
			assertThat(properties.getRememberMeTtl()).isEqualTo(Duration.ofDays(7));
			assertThat(properties.toString()).isEqualTo("FinalUserJwtProperties[redacted]");
			SecretKey key = context.getBean("finalUserSigningKey", SecretKey.class);
			assertThat(key.getEncoded()).isEqualTo(Base64.getDecoder().decode(secret));
			assertThat(key.getAlgorithm()).isEqualTo("HmacSHA512");
		});
		assertThat(output.getAll()).doesNotContain(secret);
	}

	@Test
	void bindsExternalDurations() {
		withSecret(FinalUserJwtTestSupport.generateSecret()).withPropertyValues(
				"appointment.security.final-user-jwt.ttl=2h",
				"appointment.security.final-user-jwt.remember-me-ttl=10d").run(context -> {
			assertThat(context).hasNotFailed();
			var properties = context.getBean(FinalUserJwtProperties.class);
			assertThat(properties.getTtl()).isEqualTo(Duration.ofHours(2));
			assertThat(properties.getRememberMeTtl()).isEqualTo(Duration.ofDays(10));
		});
	}

	@Test
	void missingKeyFailsStartup() {
		runner.run(context -> assertThat(context.getStartupFailure())
				.hasRootCauseMessage("Final-user JWT signing key is required"));
	}

	@ParameterizedTest
	@MethodSource("invalidKeys")
	void rejectsInvalidKeyWithoutIncludingItsValueOrDecoderCause(String secret, String message,
			CapturedOutput output) {
		withSecret(secret).run(context -> {
			assertThat(context).hasFailed();
			assertThat(context.getStartupFailure()).hasRootCauseMessage(message);
		});
		if (!secret.isBlank()) {
			assertThat(output.getAll()).doesNotContain(secret);
		}
	}

	static Stream<Arguments> invalidKeys() {
		return Stream.of(Arguments.of(" ", "Final-user JWT signing key is required"),
				Arguments.of("private-test-invalid-base64!", "Final-user JWT signing key must be valid Base64"),
				Arguments.of(Base64.getEncoder().encodeToString(new byte[63]),
						"Final-user JWT signing key requires at least 64 decoded bytes"));
	}

	@ParameterizedTest
	@MethodSource("invalidDurations")
	void rejectsInvalidDurationsWithoutLeakingTheSigningKey(String property, String value, CapturedOutput output) {
		String secret = FinalUserJwtTestSupport.generateSecret();
		withSecret(secret).withPropertyValues("appointment.security.final-user-jwt." + property + "=" + value)
				.run(context -> assertThat(context).hasFailed());
		assertThat(output.getAll()).doesNotContain(secret);
	}

	static Stream<Arguments> invalidDurations() {
		return Stream.of(Arguments.of("ttl", "0s"), Arguments.of("ttl", "-1s"),
				Arguments.of("ttl", "500ms"), Arguments.of("ttl", "not-a-duration"),
				Arguments.of("remember-me-ttl", "0s"), Arguments.of("remember-me-ttl", "-1s"),
				Arguments.of("remember-me-ttl", "500ms"), Arguments.of("remember-me-ttl", "30m"));
	}

	@Test
	void encoderProducesAnHs512SignatureThatRejectsAnotherKey() {
		withSecret(FinalUserJwtTestSupport.generateSecret()).run(context -> {
			SecretKey key = context.getBean("finalUserSigningKey", SecretKey.class);
			Instant now = Instant.now();
			var claims = JwtClaimsSet.builder().subject("user").issuedAt(now).expiresAt(now.plusSeconds(60))
					.issuer(FinalUserJwtProperties.ISSUER).audience(List.of(FinalUserJwtProperties.AUDIENCE)).build();
			String token = context.getBean(JwtEncoder.class).encode(JwtEncoderParameters.from(
					JwsHeader.with(MacAlgorithm.HS512).build(), claims)).getTokenValue();
			var decoded = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS512).build().decode(token);
			assertThat(decoded.getHeaders()).containsEntry("alg", "HS512");
			assertThat(decoded.getSubject()).isEqualTo("user");
			FinalUserJwtProperties different = new FinalUserJwtProperties();
			different.setBase64Secret(FinalUserJwtTestSupport.generateSecret());
			SecretKey other = new FinalUserJwtConfiguration().finalUserSigningKey(different);
			assertThatThrownBy(() -> NimbusJwtDecoder.withSecretKey(other).macAlgorithm(MacAlgorithm.HS512)
					.build().decode(token)).isInstanceOf(RuntimeException.class);
		});
	}

	private ApplicationContextRunner withSecret(String secret) {
		return runner.withPropertyValues(FinalUserJwtTestSupport.SECRET_PROPERTY + "=" + secret);
	}
}
