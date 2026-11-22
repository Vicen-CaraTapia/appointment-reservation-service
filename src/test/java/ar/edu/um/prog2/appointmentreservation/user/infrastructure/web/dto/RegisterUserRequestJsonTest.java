package ar.edu.um.prog2.appointmentreservation.user.infrastructure.web.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

class RegisterUserRequestJsonTest {

	private static final String VALID_JSON = """
			{"login":"User","password":"test-password","email":"User@Example.COM"}
			""";

	// Deliberately permissive globally: this DTO must still reject extra properties.
	private final JsonMapper mapper = JsonMapper.builder()
			.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();

	@Test
	void readsOnlyPublicInputAndDefaultsOmittedLanguage() {
		RegisterUserRequest request = mapper.readValue(VALID_JSON, RegisterUserRequest.class);
		assertThat(request.login()).isEqualTo("User");
		assertThat(request.password()).isEqualTo("test-password");
		assertThat(request.email()).isEqualTo("User@Example.COM");
		assertThat(request.firstName()).isNull();
		assertThat(request.lastName()).isNull();
		assertThat(request.imageUrl()).isNull();
		assertThat(request.langKey()).isEqualTo("es");
	}

	@Test
	void readsEverySupportedFieldAndExplicitNullLanguage() {
		RegisterUserRequest request = mapper.readValue("""
				{"login":"user","password":"test-password","firstName":"Ana","lastName":"Perez",
				 "email":"user@example.com","imageUrl":"https://example.com/avatar.png","langKey":null}
				""", RegisterUserRequest.class);
		assertThat(request.firstName()).isEqualTo("Ana");
		assertThat(request.lastName()).isEqualTo("Perez");
		assertThat(request.imageUrl()).isEqualTo("https://example.com/avatar.png");
		assertThat(request.langKey()).isEqualTo("es");
	}

	@ParameterizedTest
	@ValueSource(strings = {
			"\"id\":123", "\"activated\":false", "\"authorities\":[\"ROLE_ADMIN\"]",
			"\"passwordHash\":\"untrusted\"", "\"createdBy\":\"admin\"",
			"\"createdDate\":\"2026-01-01T00:00:00Z\"", "\"lastModifiedBy\":\"admin\"",
			"\"lastModifiedDate\":\"2026-01-01T00:00:00Z\"", "\"unexpected\":null",
			"\"unexpected\":{\"nested\":true}", "\"ID\":123"
	})
	void rejectsAdministrativeAndUnknownPropertiesEvenWithPermissiveMapper(String property) {
		String json = VALID_JSON.stripTrailing().replace("}", "," + property + "}");
		assertThatThrownBy(() -> mapper.readValue(json, RegisterUserRequest.class))
				.hasRootCauseInstanceOf(IllegalArgumentException.class)
				.hasRootCauseMessage("Unknown registration property");
	}

	@Test
	void neverSerializesPasswordOrPrintsRequestData() {
		RegisterUserRequest request = mapper.readValue(VALID_JSON, RegisterUserRequest.class);
		assertThat(mapper.readTree(mapper.writeValueAsString(request)).has("password")).isFalse();
		assertThat(mapper.writeValueAsString(request)).doesNotContain("test-password", "passwordHash");
		assertThat(request.toString()).isEqualTo("RegisterUserRequest[redacted]");
	}

	@Test
	void enforcesPolicyWithSpringBootConfiguredMapper() {
		new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class))
				.run(context -> {
					assertThat(context).hasNotFailed();
					JsonMapper bootMapper = context.getBean(JsonMapper.class);
					RegisterUserRequest request = bootMapper.readValue(VALID_JSON, RegisterUserRequest.class);
					assertThat(request.langKey()).isEqualTo("es");
					assertThat(bootMapper.writeValueAsString(request)).doesNotContain("test-password");
					assertThatThrownBy(() -> bootMapper.readValue(
							VALID_JSON.replace("}", ",\"authorities\":[\"ROLE_ADMIN\"]}"), RegisterUserRequest.class))
							.hasRootCauseMessage("Unknown registration property");
				});
	}
}
