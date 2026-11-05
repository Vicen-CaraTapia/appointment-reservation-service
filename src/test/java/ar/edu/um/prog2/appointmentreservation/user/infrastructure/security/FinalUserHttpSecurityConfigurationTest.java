package ar.edu.um.prog2.appointmentreservation.user.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

import java.util.Set;
import java.util.List;
import java.util.Map;
import java.time.Clock;
import java.time.Instant;
import java.time.Duration;
import java.time.ZoneOffset;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import ar.edu.um.prog2.appointmentreservation.user.application.model.RegisteredUser;
import ar.edu.um.prog2.appointmentreservation.user.application.usecase.RegisterUserUseCase;
import ar.edu.um.prog2.appointmentreservation.user.infrastructure.web.UserRegistrationController;
import ar.edu.um.prog2.appointmentreservation.support.FinalUserJwtTestSupport;

@WebMvcTest(controllers = UserRegistrationController.class,
		excludeAutoConfiguration = UserDetailsServiceAutoConfiguration.class,
		properties = "appointment.cors.allowed-origins=https://app.example.test")
@Import({FinalUserHttpSecurityConfiguration.class, FinalUserHttpSecurityConfigurationTest.JwtTestConfiguration.class,
		FinalUserHttpSecurityConfigurationTest.IdentityProbe.class})
class FinalUserHttpSecurityConfigurationTest {
	private static final String SECRET = FinalUserJwtTestSupport.generateSecret();

	@Autowired
	private MockMvc mvc;

	@MockitoBean
	private RegisterUserUseCase registration;

	@Test
	void registrationRemainsPublicWithoutCsrfOrSessionCookies() throws Exception {
		when(registration.register(any())).thenReturn(new RegisteredUser(42L, "user", null, null,
				"user@example.test", null, "es", true, Set.of("ROLE_USER")));
		mvc.perform(post("/api/register").contentType(MediaType.APPLICATION_JSON).content("""
				{"login":"user","password":"test-only-password","email":"user@example.test"}
				"""))
				.andExpect(status().isCreated())
				.andExpect(result -> assertThat(result.getRequest().getSession(false)).isNull())
				.andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));
	}

	@Test
	void authenticationAndHealthReachMvcWithoutAnAuthenticationChallenge() throws Exception {
		// These mappings are absent in this slice; 404 proves the filter allowed them through.
		mvc.perform(post("/api/authenticate").contentType(MediaType.APPLICATION_JSON).content("{}"))
				.andExpect(status().isNotFound()).andExpect(header().doesNotExist(HttpHeaders.WWW_AUTHENTICATE));
		mvc.perform(get("/actuator/health")).andExpect(status().isNotFound());
		mvc.perform(head("/actuator/health")).andExpect(status().isNotFound());
	}

	@ParameterizedTest
	@ValueSource(strings = {"/api/private", "/actuator/env", "/actuator/health/details", "/error",
			"/api/register", "/api/authenticate", "/login"})
	void allOtherRequestsFailClosedWithoutRedirectsOrSessionCookies(String path) throws Exception {
		mvc.perform(get(path)).andExpect(status().isUnauthorized())
				.andExpect(header().doesNotExist(HttpHeaders.LOCATION))
				.andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"))
				.andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));
	}

	@Test
	void authenticatedIdentityCannotAccessEndpointsOutsideThePublicUserApi() throws Exception {
		mvc.perform(get("/actuator/env").with(user("user").roles("USER"))).andExpect(status().isForbidden());
	}

	@Test
	void malformedBearerTokensAreRejectedWithoutInvokingPasswordAuthentication() throws Exception {
		mvc.perform(get("/api/private").header(HttpHeaders.AUTHORIZATION, "Bearer test-only-token"))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void httpBasicAndFormLoginAreDisabled() throws Exception {
		mvc.perform(get("/api/private").header(HttpHeaders.AUTHORIZATION, "Basic dXNlcjpwYXNz"))
				.andExpect(status().isUnauthorized()).andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"));
		mvc.perform(post("/login")).andExpect(status().isUnauthorized());
	}

	@Test
	void aRealBearerTokenEstablishesTrustedIdentityAndPersistedRoles() throws Exception {
		String token = token(SECRET, Clock.systemUTC());
		mvc.perform(get("/api/test/identity").param("userId", "999").param("login", "attacker")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.login").value("alice"))
				.andExpect(jsonPath("$.userId").value(42))
				.andExpect(jsonPath("$.authorities").value(org.hamcrest.Matchers.containsInAnyOrder("ROLE_USER", "FACTOR_BEARER")))
				.andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE))
				.andExpect(result -> assertThat(result.getRequest().getSession(false)).isNull());
		assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
		mvc.perform(get("/api/test/identity")).andExpect(status().isUnauthorized());
	}

	@Test
	void invalidExpiredAndWrongKeyTokensHaveTheSameGeneric401Body() throws Exception {
		String expected = null;
		for (String token : List.of("malformed-test-token",
				token(SECRET, Clock.fixed(Instant.now().minus(Duration.ofHours(2)), ZoneOffset.UTC)),
				token(FinalUserJwtTestSupport.generateSecret(), Clock.systemUTC()))) {
			var response = mvc.perform(get("/api/test/identity").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
					.andExpect(status().isUnauthorized())
					.andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"))
					.andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
					.andExpect(jsonPath("$.status").value(401))
					.andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
					.andReturn().getResponse();
			assertThat(response.getContentType()).startsWith(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
			if (expected == null) { expected = response.getContentAsString(); }
			assertThat(response.getContentAsString()).isEqualTo(expected).doesNotContain(token, SECRET, "Exception");
		}
	}

	@Test
	void aValidTokenDoesNotAuthorizeClosedInfrastructureEndpoints() throws Exception {
		mvc.perform(get("/actuator/env").header(HttpHeaders.AUTHORIZATION, "Bearer " + token(SECRET, Clock.systemUTC())))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.status").value(403))
				.andExpect(jsonPath("$.code").value("ACCESS_DENIED"))
				.andExpect(header().doesNotExist(HttpHeaders.WWW_AUTHENTICATE));
	}

	@Test
	void bearerCredentialsAreNotReadFromCookiesOrQueryParameters() throws Exception {
		String token = token(SECRET, Clock.systemUTC());
		mvc.perform(get("/api/test/identity").param("access_token", token)).andExpect(status().isUnauthorized());
		mvc.perform(get("/api/test/identity").cookie(new Cookie("access_token", token))).andExpect(status().isUnauthorized());
	}

	@Test
	void anExistingHttpSessionDoesNotAuthenticateTheRequest() throws Exception {
		var session = new MockHttpSession();
		var identity = UsernamePasswordAuthenticationToken.authenticated("alice", null,
				List.of(new SimpleGrantedAuthority("ROLE_USER")));
		session.setAttribute("SPRING_SECURITY_CONTEXT", new SecurityContextImpl(identity));
		mvc.perform(get("/api/test/identity").session(session)).andExpect(status().isUnauthorized());
	}

	@Test
	void corsAllowsOnlyConfiguredOriginAndExposesAuthorizationHeader() throws Exception {
		mvc.perform(options("/api/test/identity")
				.header(HttpHeaders.ORIGIN, "https://app.example.test")
				.header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
				.header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "authorization,content-type"))
				.andExpect(status().isOk())
				.andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "https://app.example.test"))
				.andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS,
						org.hamcrest.Matchers.containsString("GET")))
				.andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS,
						org.hamcrest.Matchers.containsString("authorization")))
				.andExpect(header().string(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS, "Authorization"))
				.andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS));
	}

	@Test
	void corsRejectsUnconfiguredOriginsAndWildcardConfiguration() throws Exception {
		mvc.perform(options("/api/test/identity")
				.header(HttpHeaders.ORIGIN, "https://unapproved.example.test")
				.header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
				.andExpect(status().isForbidden())
				.andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
		org.assertj.core.api.Assertions.assertThatThrownBy(() ->
				new FinalUserHttpSecurityConfiguration().finalUserSecurityFilterChain(null, null, null, "*"))
				.isInstanceOf(IllegalStateException.class)
				.hasMessage("Appointment CORS requires explicit origins; wildcard is not allowed");
	}

	private static String token(String secret, Clock clock) {
		var properties = new FinalUserJwtProperties();
		properties.setBase64Secret(secret);
		var issuer = new NimbusFinalUserTokenIssuer(properties, clock);
		return issuer.issue(new FinalUserPrincipal(42, "alice", "test-only-hash", true,
				List.of(new SimpleGrantedAuthority("ROLE_USER"))), false).value();
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class JwtTestConfiguration {
		@Bean
		JwtDecoder jwtDecoder() {
			var properties = new FinalUserJwtProperties();
			properties.setBase64Secret(SECRET);
			return new FinalUserSecurityConfiguration().finalUserJwtDecoder(properties);
		}
	}

	@RestController
	@TestComponent
	static class IdentityProbe {
		@GetMapping("/api/test/identity")
		Map<String, Object> identity(@AuthenticationPrincipal Jwt jwt, Authentication authentication) {
			return Map.of("login", jwt.getSubject(), "userId", ((Number) jwt.getClaim("userId")).longValue(),
					"authorities", authentication.getAuthorities().stream().map(authority -> authority.getAuthority()).toList());
		}
	}
}
