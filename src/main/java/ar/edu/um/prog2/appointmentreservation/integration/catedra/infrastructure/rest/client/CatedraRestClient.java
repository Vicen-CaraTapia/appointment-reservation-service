package ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.rest.client;

import java.io.IOException;
import java.net.URI;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.concurrent.TimeoutException;

import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConversionException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.ObjectMapper;

import ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.rest.dto.CatedraProblemDetails;
import ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.rest.exception.CatedraHttpException;
import ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.rest.exception.CatedraProtocolException;
import ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.rest.exception.CatedraTimeoutException;
import ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.rest.exception.CatedraTransportException;

public final class CatedraRestClient {

	private static final int MAX_PROBLEM_BYTES = 64 * 1024;
	private final RestClient http;
	private final ObjectMapper json;

	public CatedraRestClient(RestClient http, ObjectMapper json) {
		this.http = http;
		this.json = json;
	}

	public <T> @Nullable T get(String relativePath, Class<T> responseType) {
		return exchange(HttpMethod.GET, relativePath, null, responseType);
	}

	public <T> @Nullable T post(String relativePath, Object body, Class<T> responseType) {
		return exchange(HttpMethod.POST, relativePath, body, responseType);
	}

	private <T> @Nullable T exchange(HttpMethod method, String path, @Nullable Object body, Class<T> responseType) {
		validateRelativePath(path);
		try {
			var request = http.method(method).uri(path).accept(MediaType.APPLICATION_JSON);
			if (body != null) {
				request.contentType(MediaType.APPLICATION_JSON).body(body);
			}
			return request.exchange((ignored, response) -> readResponse(response, responseType));
		}
		catch (ResourceAccessException exception) {
			if (hasTimeoutCause(exception)) {
				throw new CatedraTimeoutException();
			}
			throw new CatedraTransportException();
		}
		catch (RestClientException | HttpMessageConversionException exception) {
			if (hasTimeoutCause(exception)) {
				throw new CatedraTimeoutException();
			}
			// Raw converter/transport messages may contain payloads or credentials. Do not retain their causes.
			throw new CatedraProtocolException();
		}
	}

	private <T> @Nullable T readResponse(RestClient.RequestHeadersSpec.ConvertibleClientHttpResponse response,
			Class<T> responseType) throws IOException {
		var status = response.getStatusCode();
		if (!status.is2xxSuccessful()) {
			throw new CatedraHttpException(status, problemDetails(response).code());
		}
		if (responseType == Void.class) {
			return null;
		}
		T body = response.bodyTo(responseType);
		if (body == null) {
			throw new CatedraProtocolException();
		}
		return body;
	}

	private CatedraProblemDetails problemDetails(RestClient.RequestHeadersSpec.ConvertibleClientHttpResponse response) {
		try {
			var contentType = response.getHeaders().getContentType();
			if (contentType == null || !contentType.isCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)) {
				return CatedraProblemDetails.empty();
			}
			byte[] bytes = response.getBody().readNBytes(MAX_PROBLEM_BYTES + 1);
			if (bytes.length == 0 || bytes.length > MAX_PROBLEM_BYTES) {
				return CatedraProblemDetails.empty();
			}
			var root = json.readTree(bytes);
			if (root == null || !root.isObject()) {
				return CatedraProblemDetails.empty();
			}
			var code = root.get("code");
			return new CatedraProblemDetails(code != null && code.isTextual() && !code.asText().isBlank()
					? code.asText() : null);
		}
		catch (IOException | RuntimeException exception) {
			// Even an unreadable problem body must not hide the real HTTP status.
			return CatedraProblemDetails.empty();
		}
	}

	private static void validateRelativePath(String path) {
		URI uri;
		try {
			uri = URI.create(path);
		}
		catch (IllegalArgumentException exception) {
			throw invalidPath();
		}
		if (uri.isAbsolute() || uri.getRawAuthority() != null || uri.getRawFragment() != null) {
			throw invalidPath();
		}
	}

	private static IllegalArgumentException invalidPath() {
		return new IllegalArgumentException("Catedra request path must be relative and have no authority or fragment");
	}

	private static boolean hasTimeoutCause(Throwable failure) {
		for (Throwable current = failure; current != null; current = current.getCause()) {
			if (current instanceof HttpTimeoutException || current instanceof SocketTimeoutException
					|| current instanceof TimeoutException) {
				return true;
			}
		}
		return false;
	}
}
