package ar.edu.um.prog2.appointmentreservation.integration.catalog.infrastructure.security;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.RSAPublicKeySpec;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.springframework.security.converter.RsaKeyConverters;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtEncodingException;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import ar.edu.um.prog2.appointmentreservation.integration.catalog.infrastructure.config.CatalogIntegrationProperties;

public final class CatalogInternalTokenIssuer {

	private final JwtEncoder encoder;
	private final CatalogIntegrationProperties properties;
	private final Clock clock;

	public CatalogInternalTokenIssuer(CatalogIntegrationProperties properties, Clock clock) {
		if (properties.tokenTtl() == null || !properties.isTokenTtlValid()) {
			throw new IllegalStateException("Catalog internal JWT TTL must be a positive whole number of seconds");
		}
		this.encoder = createEncoder(properties.privateKeyLocation());
		this.properties = properties;
		this.clock = clock;
	}

	public String issue() {
		var issuedAt = clock.instant().truncatedTo(ChronoUnit.SECONDS);
		var claims = JwtClaimsSet.builder()
				.issuer(properties.issuer()).audience(List.of(properties.audience()))
				.subject("appointment-reservation-service").claim("scope", "catalog:read")
				.issuedAt(issuedAt).expiresAt(issuedAt.plus(properties.tokenTtl())).build();
		try {
			return encoder.encode(JwtEncoderParameters.from(
					JwsHeader.with(SignatureAlgorithm.RS256).build(), claims)).getTokenValue();
		} catch (JwtEncodingException exception) {
			throw new IllegalStateException("Catalog internal JWT could not be issued");
		}
	}

	private static JwtEncoder createEncoder(String location) {
		try {
			var uri = URI.create(location);
			if (!"file".equals(uri.getScheme())) {
				throw new IllegalArgumentException();
			}
			try (var input = Files.newInputStream(Path.of(uri))) {
				var key = (RSAPrivateCrtKey) RsaKeyConverters.pkcs8().convert(input);
				if (key == null || key.getModulus().bitLength() < 2048) {
					throw new IllegalArgumentException();
				}
				var publicKey = (RSAPublicKey) KeyFactory.getInstance("RSA").generatePublic(
						new RSAPublicKeySpec(key.getModulus(), key.getPublicExponent()));
				return NimbusJwtEncoder.withKeyPair(publicKey, key).algorithm(SignatureAlgorithm.RS256).build();
			}
		} catch (Exception exception) {
			// Parser and I/O causes may contain paths or key material; do not propagate them.
			throw new IllegalStateException("Catalog internal JWT signing requires a valid external RSA private key (PEM PKCS#8, at least 2048 bits)");
		}
	}
}
