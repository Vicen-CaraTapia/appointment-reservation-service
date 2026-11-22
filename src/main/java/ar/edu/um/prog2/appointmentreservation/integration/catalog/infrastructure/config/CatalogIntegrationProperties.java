package ar.edu.um.prog2.appointmentreservation.integration.catalog.infrastructure.config;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@ConfigurationProperties(prefix = "catalog.integration")
@Validated
public record CatalogIntegrationProperties(
		@NotBlank String baseUrl,
		@NotNull @DurationMin(seconds = 0, inclusive = false) Duration connectTimeout,
		@NotNull @DurationMin(seconds = 0, inclusive = false) Duration readTimeout,
		@NotBlank String privateKeyLocation,
		@NotBlank String issuer,
		@NotBlank String audience,
		@NotNull Duration tokenTtl
) {

	@AssertTrue(message = "Catalog base URL must be an absolute HTTP(S) URL without credentials, query or fragment")
	public boolean isBaseUrlValid() {
		if (baseUrl == null || baseUrl.isBlank()) {
			return true; // @NotBlank reports missing values on the configuration field.
		}
		try {
			var uri = URI.create(baseUrl);
			return ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
					&& uri.getHost() != null && uri.getUserInfo() == null
					&& uri.getRawQuery() == null && uri.getRawFragment() == null
					&& (uri.getPort() == -1 || (uri.getPort() > 0 && uri.getPort() <= 65535));
		} catch (IllegalArgumentException exception) {
			return false;
		}
	}

	/** Checks the location syntax; the token issuer loads and validates the key. */
	@AssertTrue(message = "Catalog private key location must be an absolute external file URI")
	public boolean isPrivateKeyLocationValid() {
		if (privateKeyLocation == null || privateKeyLocation.isBlank()) {
			return true;
		}
		try {
			var uri = URI.create(privateKeyLocation);
			return "file".equals(uri.getScheme()) && Path.of(uri).isAbsolute();
		} catch (IllegalArgumentException exception) {
			return false;
		}
	}

	@AssertTrue(message = "Catalog internal JWT TTL must be a positive whole number of seconds")
	public boolean isTokenTtlValid() {
		return tokenTtl == null || (!tokenTtl.isNegative() && !tokenTtl.isZero() && tokenTtl.getNano() == 0);
	}

	@Override
	public String toString() {
		return "CatalogIntegrationProperties[redacted]";
	}
}
