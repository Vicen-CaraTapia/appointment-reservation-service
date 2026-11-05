package ar.edu.um.prog2.appointmentreservation.user.infrastructure.security;

import java.math.BigDecimal;
import java.text.ParseException;
import java.time.Instant;
import java.util.List;

import com.nimbusds.jwt.SignedJWT;

import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.BadJwtException;

/** Validates the final-user claim contract independently of HTTP authorization. */
final class FinalUserJwtValidator implements OAuth2TokenValidator<Jwt> {

	static void validateOriginalClaimTypes(String token) {
		try {
			// Called only after Nimbus has verified the signature and decoded claim contract.
			var claims = SignedJWT.parse(token).getPayload().toJSONObject();
			Object audience = claims.get("aud");
			boolean audienceType = audience instanceof String || validAudience(audience);
			if (!(claims.get("sub") instanceof String) || !audienceType
					|| !(claims.get("exp") instanceof Number)
					|| (claims.containsKey("nbf") && !(claims.get("nbf") instanceof Number))) {
				throw new BadJwtException("Invalid final-user JWT claim types");
			}
		}
		catch (ParseException failure) {
			throw new BadJwtException("Invalid final-user JWT claim types");
		}
	}

	@Override
	public OAuth2TokenValidatorResult validate(Jwt jwt) {
		var claims = jwt.getClaims();
		if (FinalUserJwtProperties.ISSUER.equals(claims.get("iss"))
				&& validAudience(claims.get("aud"))
				&& claims.get("exp") instanceof Instant
				&& claims.get("sub") instanceof String subject && !subject.isBlank()
				&& validUserId(claims.get("userId"))
				&& validAuthorities(claims.get("auth"))) {
			return OAuth2TokenValidatorResult.success();
		}
		// Fixed description: no rejected claim values or credentials in errors.
		return OAuth2TokenValidatorResult.failure(
				new OAuth2Error("invalid_token", "Invalid final-user JWT claims", null));
	}

	private static boolean validAudience(Object value) {
		return value instanceof List<?> audience && !audience.isEmpty()
				&& audience.stream().allMatch(item -> item instanceof String)
				&& audience.contains(FinalUserJwtProperties.AUDIENCE);
	}

	private static boolean validUserId(Object value) {
		if (!(value instanceof Number number)) {
			return false;
		}
		try {
			return new BigDecimal(number.toString()).longValueExact() > 0;
		}
		catch (NumberFormatException | ArithmeticException failure) {
			return false;
		}
	}

	private static boolean validAuthorities(Object value) {
		if (!(value instanceof String authorities) || authorities.isEmpty()) {
			return false;
		}
		for (String authority : authorities.split(" ", -1)) {
			if (!authority.matches("ROLE_[A-Z][A-Z0-9_]*")) {
				return false;
			}
		}
		return true;
	}
}
