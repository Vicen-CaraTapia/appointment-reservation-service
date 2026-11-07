package ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.rest.config;

import java.net.URI;
import java.net.http.HttpClient;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.config.CatedraIntegrationProperties;

@Configuration(proxyBeanMethods = false)
public class CatedraRestClientConfiguration {

	@Bean
	public RestClient catedraHttpClient(CatedraIntegrationProperties properties) {
		var rest = properties.rest();
		URI baseUri = validatedBaseUri(rest.baseUrl());
		HttpClient httpClient = HttpClient.newBuilder()
				.connectTimeout(rest.connectTimeout())
				.followRedirects(HttpClient.Redirect.NEVER)
				.build();
		var requestFactory = new JdkClientHttpRequestFactory(httpClient);
		requestFactory.setReadTimeout(rest.readTimeout());

		return RestClient.builder()
				.baseUrl(baseUri.toString())
				.requestFactory(requestFactory)
				.requestInterceptor((request, body, execution) -> {
					if (!sameOrigin(baseUri, request.getURI())) {
						throw new IllegalArgumentException("Catedra requests must use the configured REST origin");
					}
					request.getHeaders().setBearerAuth(properties.technicalJwt());
					return execution.execute(request, body);
				})
				.build();
	}

	private static URI validatedBaseUri(String baseUrl) {
		URI uri;
		try {
			uri = URI.create(baseUrl);
		}
		catch (IllegalArgumentException exception) {
			// Do not include the supplied URL or its parser exception: it may contain credentials.
			throw invalidBaseUrl();
		}
		if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
				|| uri.getHost() == null || uri.getUserInfo() != null
				|| uri.getRawQuery() != null || uri.getRawFragment() != null
				|| uri.getPort() < -1 || uri.getPort() == 0 || uri.getPort() > 65535) {
			throw invalidBaseUrl();
		}
		return uri;
	}

	private static IllegalArgumentException invalidBaseUrl() {
		return new IllegalArgumentException(
				"Catedra REST base URL must be an absolute HTTP(S) URL without credentials, query or fragment");
	}

	private static boolean sameOrigin(URI expected, URI actual) {
		return expected.getScheme().equalsIgnoreCase(actual.getScheme())
				&& expected.getHost().equalsIgnoreCase(actual.getHost())
				&& port(expected) == port(actual)
				&& actual.getUserInfo() == null;
	}

	private static int port(URI uri) {
		return uri.getPort() == -1 ? ("https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80) : uri.getPort();
	}
}
