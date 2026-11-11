package ar.edu.um.prog2.appointmentreservation.integration.catalog.infrastructure.config;

import java.net.http.HttpClient;
import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import ar.edu.um.prog2.appointmentreservation.integration.catalog.infrastructure.rest.client.CatalogProfessionalClient;
import ar.edu.um.prog2.appointmentreservation.integration.catalog.infrastructure.security.CatalogInternalTokenIssuer;

@Configuration(proxyBeanMethods = false)
public class CatalogIntegrationConfiguration {

	@Bean
	public CatalogInternalTokenIssuer catalogInternalTokenIssuer(CatalogIntegrationProperties properties) {
		return new CatalogInternalTokenIssuer(properties, Clock.systemUTC());
	}

	@Bean
	public CatalogProfessionalClient catalogProfessionalClient(CatalogIntegrationProperties properties, CatalogInternalTokenIssuer tokens) {
		var transport = HttpClient.newBuilder().connectTimeout(properties.connectTimeout())
				.followRedirects(HttpClient.Redirect.NEVER).build();
		var requestFactory = new JdkClientHttpRequestFactory(transport);
		requestFactory.setReadTimeout(properties.readTimeout());
		var http = RestClient.builder().baseUrl(properties.baseUrl()).requestFactory(requestFactory)
				.requestInterceptor((request, body, execution) -> {
					request.getHeaders().setBearerAuth(tokens.issue());
					return execution.execute(request, body);
				}).build();
		return new CatalogProfessionalClient(http);
	}
}
