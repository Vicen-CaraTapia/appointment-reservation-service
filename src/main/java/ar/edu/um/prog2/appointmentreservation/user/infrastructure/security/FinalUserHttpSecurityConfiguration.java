package ar.edu.um.prog2.appointmentreservation.user.infrastructure.security;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;

import tools.jackson.databind.json.JsonMapper;

@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class FinalUserHttpSecurityConfiguration {

	@Bean
	public SecurityFilterChain finalUserSecurityFilterChain(HttpSecurity http, JwtDecoder decoder, JsonMapper json)
			throws Exception {
		AuthenticationEntryPoint unauthorized = (request, response, failure) -> securityError(response, json, HttpStatus.UNAUTHORIZED);
		AccessDeniedHandler forbidden = (request, response, failure) -> securityError(response, json, HttpStatus.FORBIDDEN);
		var authorities = new JwtGrantedAuthoritiesConverter();
		authorities.setAuthoritiesClaimName("auth");
		authorities.setAuthorityPrefix("");
		var authentication = new JwtAuthenticationConverter();
		authentication.setJwtGrantedAuthoritiesConverter(authorities);
		return http
				// Authentication uses explicit credentials/JWTs, not browser session cookies.
				.csrf(AbstractHttpConfigurer::disable)
				.formLogin(AbstractHttpConfigurer::disable)
				.httpBasic(AbstractHttpConfigurer::disable)
				.logout(AbstractHttpConfigurer::disable)
				.requestCache(AbstractHttpConfigurer::disable)
				.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				.exceptionHandling(errors -> errors.authenticationEntryPoint(unauthorized).accessDeniedHandler(forbidden))
				.oauth2ResourceServer(resource -> resource
						.jwt(jwt -> jwt.decoder(decoder).jwtAuthenticationConverter(authentication))
						.authenticationEntryPoint(unauthorized).accessDeniedHandler(forbidden))
				.authorizeHttpRequests(access -> access
						// Only container error rendering; a direct request to /error remains denied.
						.dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
						.requestMatchers(HttpMethod.POST, "/api/register", "/api/authenticate").permitAll()
						.requestMatchers(HttpMethod.GET, "/actuator/health").permitAll()
						.requestMatchers(HttpMethod.HEAD, "/actuator/health").permitAll()
						.requestMatchers("/api/**").authenticated()
						.anyRequest().denyAll())
				.build();
	}

	private static void securityError(HttpServletResponse response, JsonMapper json, HttpStatus status) throws IOException {
		boolean unauthorized = status == HttpStatus.UNAUTHORIZED;
		response.setStatus(status.value());
		response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
		response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
		if (unauthorized) {
			response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
		}
		var body = ProblemDetail.forStatusAndDetail(status,
				unauthorized ? "A valid final-user Bearer token is required" : "Access denied");
		body.setProperty("code", unauthorized ? "UNAUTHORIZED" : "ACCESS_DENIED");
		json.writeValue(response.getOutputStream(), body);
	}
}
