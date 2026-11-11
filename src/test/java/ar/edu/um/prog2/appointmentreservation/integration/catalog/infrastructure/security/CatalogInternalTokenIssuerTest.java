package ar.edu.um.prog2.appointmentreservation.integration.catalog.infrastructure.security;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import ar.edu.um.prog2.appointmentreservation.integration.catalog.infrastructure.config.CatalogIntegrationProperties;
import ar.edu.um.prog2.appointmentreservation.support.CatalogJwtTestSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(OutputCaptureExtension.class)
class CatalogInternalTokenIssuerTest {

	private static final Instant NOW = Instant.parse("2030-01-02T03:04:05.123Z");
	private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
	private static final String PRIVATE_INPUT = "test-only-private-diagnostic";

	@TempDir
	Path directory;

	private Path keyFile;

	@BeforeEach
	void writeEphemeralKey() throws IOException {
		keyFile = CatalogJwtTestSupport.writePrivateKey(directory, CatalogJwtTestSupport.signingKeyPair().getPrivate());
	}

	@Test
	void issuesARealRs256SignatureAndOnlyTheInternalContractClaims() {
		var token = issuer(Duration.ofMinutes(5)).issue();
		var jwt = decoder((RSAPublicKey) CatalogJwtTestSupport.signingKeyPair().getPublic(), CLOCK).decode(token);

		assertThat(jwt.getHeaders().get("alg")).isEqualTo("RS256");
		assertThat(jwt.getClaimAsString("iss")).isEqualTo("appointment-reservation-service");
		assertThat(jwt.getSubject()).isEqualTo("appointment-reservation-service");
		assertThat(jwt.getAudience()).containsExactly("catalog-sync-service");
		assertThat(jwt.getClaimAsString("scope")).isEqualTo("catalog:read");
		assertThat(jwt.getIssuedAt()).isEqualTo(Instant.parse("2030-01-02T03:04:05Z"));
		assertThat(jwt.getExpiresAt()).isEqualTo(jwt.getIssuedAt().plusSeconds(300));
		assertThat(jwt.getClaims()).containsOnlyKeys("iss", "sub", "aud", "scope", "iat", "exp");
	}

	@ParameterizedTest
	@ValueSource(longs = {1, 75, 300, 900})
	void usesTheConfiguredLifetime(long seconds) {
		var jwt = decoder((RSAPublicKey) CatalogJwtTestSupport.signingKeyPair().getPublic(), CLOCK)
				.decode(issuer(Duration.ofSeconds(seconds)).issue());
		assertThat(Duration.between(jwt.getIssuedAt(), jwt.getExpiresAt())).isEqualTo(Duration.ofSeconds(seconds));
	}

	@Test
	void tokenExpiresWithoutRelyingOnWallClockTime() {
		var token = issuer(Duration.ofMinutes(5)).issue();
		var afterExpiration = Clock.fixed(NOW.plusSeconds(301), ZoneOffset.UTC);
		var decoder = decoder((RSAPublicKey) CatalogJwtTestSupport.signingKeyPair().getPublic(), afterExpiration);
		assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(JwtException.class);
	}

	@Test
	void anotherRsaPublicKeyCannotVerifyTheSignature() {
		var token = issuer(Duration.ofMinutes(5)).issue();
		var wrongKey = (RSAPublicKey) CatalogJwtTestSupport.generateRsaKeyPair(2048).getPublic();
		var decoder = decoder(wrongKey, CLOCK);
		assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(JwtException.class);
	}

	@Test
	void buildsFreshClaimsForEachIssuanceWithoutCachingAToken() {
		var clock = mock(Clock.class);
		when(clock.instant()).thenReturn(NOW, NOW.plusSeconds(60));
		var issuer = new CatalogInternalTokenIssuer(properties(keyFile.toUri().toString(), Duration.ofMinutes(5)), clock);
		var decoder = decoder((RSAPublicKey) CatalogJwtTestSupport.signingKeyPair().getPublic(), CLOCK);
		var first = decoder.decode(issuer.issue());
		var second = decoder.decode(issuer.issue());
		assertThat(second.getIssuedAt()).isEqualTo(first.getIssuedAt().plusSeconds(60));
		assertThat(second.getExpiresAt()).isEqualTo(first.getExpiresAt().plusSeconds(60));
	}

	@Test
	void loadsTheKeyOnceAndDoesNotReopenItForEachToken() throws IOException {
		var issuer = issuer(Duration.ofMinutes(5));
		Files.delete(keyFile);
		assertThat(decoder((RSAPublicKey) CatalogJwtTestSupport.signingKeyPair().getPublic(), CLOCK)
				.decode(issuer.issue()).getSubject()).isEqualTo("appointment-reservation-service");
	}

	@Test
	void rejectsAMissingKeyWithASanitizedFailure() {
		assertInvalidKey(directory.resolve(PRIVATE_INPUT).toUri().toString());
	}

	@ParameterizedTest
	@ValueSource(strings = {"", "not-a-key-test-only-private-diagnostic",
			"-----BEGIN RSA PRIVATE KEY-----\ntest-only-private-diagnostic\n-----END RSA PRIVATE KEY-----",
			"-----BEGIN ENCRYPTED PRIVATE KEY-----\ntest-only-private-diagnostic\n-----END ENCRYPTED PRIVATE KEY-----",
			"-----BEGIN PRIVATE KEY-----\ninvalid-base64!\n-----END PRIVATE KEY-----"})
	void rejectsInvalidPemPkcs1AndEncryptedKeys(String pem) throws IOException {
		Files.writeString(keyFile, pem);
		assertInvalidKey(keyFile.toUri().toString());
	}

	@Test
	void rejectsANonRsaPkcs8PrivateKey() throws Exception {
		var ec = KeyPairGenerator.getInstance("EC");
		ec.initialize(256);
		var path = CatalogJwtTestSupport.writePrivateKey(directory, ec.generateKeyPair().getPrivate());
		assertInvalidKey(path.toUri().toString());
	}

	@Test
	void rejectsRsaKeysShorterThan2048Bits() throws IOException {
		var path = CatalogJwtTestSupport.writePrivateKey(directory, CatalogJwtTestSupport.generateRsaKeyPair(1024).getPrivate());
		assertInvalidKey(path.toUri().toString());
	}

	@ParameterizedTest
	@ValueSource(strings = {"https://example.test/test-only-private-diagnostic", "classpath:private.pem", "file:relative.pem"})
	void rejectsLocationsThatAreNotExternalAbsoluteFiles(String location) {
		assertInvalidKey(location);
	}

	@ParameterizedTest
	@ValueSource(strings = {"PT0S", "PT-1S", "PT0.5S"})
	@NullSource
	void rejectsInvalidTtlBeforeIssuingTokens(String ttl) {
		var duration = ttl == null ? null : Duration.parse(ttl);
		assertThatThrownBy(() -> issuer(duration)).isInstanceOf(IllegalStateException.class)
				.hasMessage("Catalog internal JWT TTL must be a positive whole number of seconds").hasNoCause();
	}

	@AfterEach
	void neverLogsThePrivateKeyOrItsLocation(CapturedOutput output) {
		assertThat(output.getAll()).doesNotContain(keyFile.toString(), PRIVATE_INPUT,
				Base64.getEncoder().encodeToString(CatalogJwtTestSupport.signingKeyPair().getPrivate().getEncoded()));
	}

	private CatalogInternalTokenIssuer issuer(Duration ttl) {
		return new CatalogInternalTokenIssuer(properties(keyFile.toUri().toString(), ttl), CLOCK);
	}

	private void assertInvalidKey(String location) {
		assertThatThrownBy(() -> new CatalogInternalTokenIssuer(properties(location, Duration.ofMinutes(5)), CLOCK))
				.isInstanceOf(IllegalStateException.class).hasNoCause()
				.hasMessage("Catalog internal JWT signing requires a valid external RSA private key (PEM PKCS#8, at least 2048 bits)");
	}

	private static CatalogIntegrationProperties properties(String location, Duration ttl) {
		return new CatalogIntegrationProperties("https://catalog.example.test", Duration.ofSeconds(5), Duration.ofSeconds(30),
				location, "appointment-reservation-service", "catalog-sync-service", ttl);
	}

	private static NimbusJwtDecoder decoder(RSAPublicKey publicKey, Clock clock) {
		var decoder = NimbusJwtDecoder.withPublicKey(publicKey).signatureAlgorithm(SignatureAlgorithm.RS256).build();
		var validator = new JwtTimestampValidator(Duration.ZERO);
		validator.setClock(clock);
		decoder.setJwtValidator(validator);
		return decoder;
	}
}
