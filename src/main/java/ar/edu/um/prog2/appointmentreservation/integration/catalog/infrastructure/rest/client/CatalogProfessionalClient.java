package ar.edu.um.prog2.appointmentreservation.integration.catalog.infrastructure.rest.client;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.concurrent.TimeoutException;

import org.springframework.http.MediaType;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.ObjectReader;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.cfg.CoercionAction;
import tools.jackson.databind.cfg.CoercionInputShape;
import tools.jackson.databind.cfg.EnumFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.module.SimpleModule;
import tools.jackson.databind.type.LogicalType;

import ar.edu.um.prog2.appointmentreservation.integration.catalog.infrastructure.rest.dto.CatalogProfessionalResponse;
import ar.edu.um.prog2.appointmentreservation.integration.catalog.infrastructure.rest.exception.CatalogClientException;

public final class CatalogProfessionalClient {

	private final RestClient http;
	// Strict decoding is local to this external boundary; public API JSON settings are unchanged.
	private final ObjectReader reader = JsonMapper.builder()
			.disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
			.disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
			.enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES,
					DeserializationFeature.FAIL_ON_NULL_CREATOR_PROPERTIES,
					DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES,
					DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
			.enable(EnumFeature.FAIL_ON_NUMBERS_FOR_ENUMS)
			.addModule(new SimpleModule().addDeserializer(LocalTime.class, new IsoLocalTimeDeserializer()))
			.withCoercionConfig(LogicalType.Textual, config -> config
					.setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
					.setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
					.setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail))
			.build().readerFor(CatalogProfessionalResponse.class);

	public CatalogProfessionalClient(RestClient http) {
		this.http = http;
	}

	public CatalogProfessionalResponse getProfessional(long id) {
		try {
			return http.get().uri("/internal/api/professionals/{id}", id).accept(MediaType.APPLICATION_JSON)
					.exchange((request, response) -> {
						var status = response.getStatusCode();
						if (!status.is2xxSuccessful()) {
							throw CatalogClientException.http(status.value());
						}
						var type = response.getHeaders().getContentType();
						if (status.value() != 200 || type == null || !MediaType.APPLICATION_JSON.isCompatibleWith(type)) {
							throw CatalogClientException.invalidResponse();
						}
						CatalogProfessionalResponse professional = reader.readValue(response.getBody());
						if (professional == null || professional.id() != id) {
							throw CatalogClientException.invalidResponse();
						}
						return professional;
					});
		} catch (RestClientException | JacksonException | IllegalArgumentException exception) {
			// Raw messages can contain response data or Authorization; do not retain causes.
			throw translateFailure(exception);
		}
	}

	private static CatalogClientException translateFailure(RuntimeException failure) {
		boolean transportFailure = failure instanceof ResourceAccessException;
		for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
			if (cause instanceof HttpTimeoutException || cause instanceof SocketTimeoutException || cause instanceof TimeoutException) {
				return CatalogClientException.timeout();
			}
			transportFailure |= cause instanceof IOException;
		}
		return transportFailure ? CatalogClientException.transport() : CatalogClientException.invalidResponse();
	}

	/** Catalog sends local-time strings; Jackson's default also accepts arrays and date-times. */
	private static final class IsoLocalTimeDeserializer extends ValueDeserializer<LocalTime> {
		@Override
		public LocalTime deserialize(JsonParser parser, DeserializationContext context) {
			if (parser.hasToken(JsonToken.VALUE_STRING)) {
				try {
					return LocalTime.parse(parser.getString(), DateTimeFormatter.ISO_LOCAL_TIME);
				} catch (DateTimeParseException exception) {
					// Do not include the external value in diagnostics.
				}
			}
			return context.reportInputMismatch(LocalTime.class, "Catalog time must be an ISO local-time string without date or offset");
		}
	}
}
