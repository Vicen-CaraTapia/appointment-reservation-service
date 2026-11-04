package ar.edu.um.prog2.appointmentreservation.user.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import ar.edu.um.prog2.appointmentreservation.user.application.model.RegisteredUser;
import ar.edu.um.prog2.appointmentreservation.user.application.usecase.RegisterUserUseCase;
import ar.edu.um.prog2.appointmentreservation.user.infrastructure.web.UserRegistrationController;

@WebMvcTest(controllers = UserRegistrationController.class,
		excludeAutoConfiguration = UserDetailsServiceAutoConfiguration.class)
@Import(FinalUserHttpSecurityConfiguration.class)
class FinalUserHttpSecurityConfigurationTest {

	@Autowired
	private MockMvc mvc;

	@MockitoBean
	private RegisterUserUseCase registration;

	@MockitoBean
	private AuthenticationManager authenticationManager;

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
		verifyNoInteractions(authenticationManager);
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
				.andExpect(header().doesNotExist(HttpHeaders.WWW_AUTHENTICATE))
				.andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));
	}

	@Test
	void authenticatedIdentityCannotAccessClosedEndpointsBeforeA24() throws Exception {
		mvc.perform(get("/api/private").with(user("user").roles("USER"))).andExpect(status().isForbidden());
	}

	@Test
	void bearerTokensAreNotProcessedBeforeA24() throws Exception {
		mvc.perform(get("/api/private").header(HttpHeaders.AUTHORIZATION, "Bearer test-only-token"))
				.andExpect(status().isUnauthorized());
		verifyNoInteractions(authenticationManager);
	}

	@Test
	void httpBasicAndFormLoginAreDisabled() throws Exception {
		mvc.perform(get("/api/private").header(HttpHeaders.AUTHORIZATION, "Basic dXNlcjpwYXNz"))
				.andExpect(status().isUnauthorized()).andExpect(header().doesNotExist(HttpHeaders.WWW_AUTHENTICATE));
		mvc.perform(post("/login")).andExpect(status().isUnauthorized());
		verifyNoInteractions(authenticationManager);
	}
}
