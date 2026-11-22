package ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.rest.client;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.json.JsonMapper;
import ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.config.CatedraIntegrationProperties;
import ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.rest.config.CatedraRestClientConfiguration;
import ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.rest.adapter.CatedraTechnicalReservationAdapter;
import ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.rest.exception.CatedraProtocolException;
import ar.edu.um.prog2.appointmentreservation.reservation.application.model.*;
import ar.edu.um.prog2.appointmentreservation.reservation.application.exception.ReservationListingException;
import static org.assertj.core.api.Assertions.*;

@org.junit.jupiter.api.extension.ExtendWith(org.springframework.boot.test.system.OutputCaptureExtension.class)
class CatedraReservationListingClientTest {
	private HttpServer server;
	private final AtomicReference<String> target = new AtomicReference<>();
	private final AtomicReference<String> authorization = new AtomicReference<>();
	private int requests;
	private static final String ROW = """
		{"reservationId":9223372036854775807,"reservationProcessId":"process-1","externalPatientId":"user-7",
		"appointmentDate":"2026-12-01","startTime":"11:30:00","endTime":"12:00:00","professionalId":202,
		"professionalFirstName":"Private professional","patientPhone":null,"status":"PHONE_PENDING",
		"createdAt":"2026-11-22T10:00:00Z","confirmedAt":null,"cancelledAt":null,"cancellationReason":null}
		""";
	private static ReservationListQuery all() { return new ReservationListQuery(null,null,null,null,null,null,null); }
	@AfterEach void stop(org.springframework.boot.test.system.CapturedOutput output) {
		if (server != null) { server.stop(0); }
		assertThat(output.getAll()).doesNotContain("test-private-token", "Private professional", "private detail");
	}

	@Test void readsTechnicalRowsWithoutReorderingAndMapsNullableFields() throws Exception {
		var client = start(200, "[" + ROW + "," + ROW.replace("process-1","process-2").replace("user-7","user-8") + "]", "12");
		var page = new CatedraTechnicalReservationAdapter(client).list(all());
		assertThat(target.get()).isEqualTo("/base/api/appointments");
		assertThat(authorization.get()).isEqualTo("Bearer test-private-token");
		assertThat(page.totalCount()).isEqualTo(12);
		assertThat(page.reservations()).extracting(TechnicalReservation::reservationProcessId).containsExactly("process-1","process-2");
		var row = page.reservations().getFirst();
		assertThat(row.reservationId()).isEqualTo(Long.MAX_VALUE);
		assertThat(row.status()).isEqualTo(TechnicalReservation.Status.PHONE_PENDING);
		assertThat(row.appointmentDate()).isEqualTo(LocalDate.of(2026,12,1));
		assertThat(row.patientPhone()).isNull(); assertThat(row.confirmedAt()).isNull();
		assertThat(row.toString() + page + all()).doesNotContain("user-7", "Private professional");
		assertThat(requests).isEqualTo(1);
	}
	@Test void encodesAllFiltersOnceAndDoesNotFollowLinks() throws Exception {
		var client = start(200,"[]","0");
		var query = new ReservationListQuery(TechnicalReservation.Status.CONFIRMED,LocalDate.of(2026,12,1),
				LocalDate.of(2026,12,2),"user+ &%{ñ}",2,5,List.of("appointmentDate,desc","startTime,desc"));
		client.list(query);
		assertThat(target.get()).isEqualTo("/base/api/appointments?status=CONFIRMED&from=2026-12-01&to=2026-12-02&externalPatientId=user%2B%20%26%25%7B%C3%B1%7D&page=2&size=5&sort=appointmentDate%2Cdesc&sort=startTime%2Cdesc");
		assertThat(query.toString()).doesNotContain("user+"); assertThat(requests).isEqualTo(1);
	}
	@ParameterizedTest @ValueSource(strings={"PHONE_PENDING","CONFIRMED","CANCELLED","FAILED"})
	void acceptsEveryDocumentedStatusAndCompatibleExtraFields(String status) throws Exception {
		var client=start(200,"["+ROW.replace("PHONE_PENDING",status).replace("\"reservationId\":", "\"futureField\":{},\"reservationId\":")+"]","1");
		assertThat(client.list(all()).reservations().getFirst().status()).isEqualTo(status);
	}
	@ParameterizedTest @ValueSource(strings={"MISSING","-1","abc","1.0","9223372036854775808","1,2",""})
	void rejectsInvalidPagination(String count) throws Exception {
		var client=start(200,"[]",count.equals("MISSING")?null:count);
		assertThatThrownBy(()->client.list(all())).isInstanceOf(CatedraProtocolException.class).hasNoCause();
	}
	@ParameterizedTest @ValueSource(strings={"{}","[null]","[1]","[{}]"})
	void rejectsMalformedResponse(String body) throws Exception {
		var client=start(200,body,"1");
		assertThatThrownBy(()->client.list(all())).isInstanceOf(CatedraProtocolException.class);
	}
	@ParameterizedTest @CsvSource(delimiter='|',value={
		"9223372036854775807|\"9223372036854775807\"", "9223372036854775807|1.5", "9223372036854775807|9223372036854775808",
		"PHONE_PENDING|UNKNOWN", "process-1|' '", "2026-12-01|invalid", "11:30:00|25:00:00", "2026-11-22T10:00:00Z|invalid",
		"\"patientPhone\":null|\"patientPhone\":123", "\"confirmedAt\":null|\"confirmedAt\":\"bad\""})
	void rejectsInvalidRowWithoutExposingInput(String original,String replacement) throws Exception {
		var client=start(200,"["+ROW.replace(original,replacement)+"]","1");
		assertThatThrownBy(()->client.list(all())).isInstanceOf(CatedraProtocolException.class).hasNoCause()
				.hasMessageNotContaining("Private professional");
	}
	@Test void rejectsRowsOutsideExactPatientFilter() throws Exception {
		var client=start(200,"["+ROW+"]","1");
		assertThatThrownBy(()->client.list(new ReservationListQuery(null,null,null,"USER-7",null,null,null)))
				.isInstanceOf(CatedraProtocolException.class);
	}
	@ParameterizedTest @CsvSource({"400,INVALID_DATE_RANGE,INVALID_DATE_RANGE","403,STUDENT_INTEGRATION_NOT_FOUND,INTEGRATION_NOT_FOUND",
		"401,UNKNOWN,UNAUTHORIZED","403,UNKNOWN,FORBIDDEN","404,UNKNOWN,NOT_FOUND","409,UNKNOWN,CONFLICT",
		"422,UNKNOWN,REQUEST_REJECTED","500,UNKNOWN,UNAVAILABLE","503,UNKNOWN,UNAVAILABLE"})
	void translatesByStatusAndCodeWithoutDetailOrRetries(int status,String code,String reason) throws Exception {
		var client=start(status,"{\"code\":\""+code+"\",\"detail\":\"private detail\"}",null);
		assertThatThrownBy(()->new CatedraTechnicalReservationAdapter(client).list(all()))
				.isInstanceOfSatisfying(ReservationListingException.class,error->assertThat(error.reason().name()).isEqualTo(reason))
				.hasNoCause().hasMessageNotContaining("private detail");
		assertThat(requests).isEqualTo(1);
	}
	@Test void validatesOnlyProvidedQueryBounds() {
		assertThatThrownBy(()->new ReservationListQuery(null,LocalDate.MAX,LocalDate.MIN,null,null,null,null)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(()->new ReservationListQuery(null,null,null,null,-1,null,null)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(()->new ReservationListQuery(null,null,null,null,null,0,null)).isInstanceOf(IllegalArgumentException.class);
		assertThat(new ReservationListQuery(null,LocalDate.MIN,null,null,0,1,null).sort()).isEmpty();
	}
	@Test void preservesRawLinkMetadataButRedactsRepresentations() throws Exception {
		var client=start(200,"["+ROW+"]","1");
		var page=client.list(all());
		assertThat(page.links()).containsExactly("<https://untrusted.example/next>; rel=\"next\"");
		assertThat(page.toString()+page.reservations().getFirst()).doesNotContain("Private professional", "user-7");
	}
	@ParameterizedTest @CsvSource({"CONFIRMED,2026-12-01,2026-12-01", "PHONE_PENDING,2026-12-02,2026-12-03", "PHONE_PENDING,2026-11-01,2026-11-30"})
	void rejectsStatusAndDateFilterViolations(TechnicalReservation.Status status,LocalDate from,LocalDate to) throws Exception {
		var client=start(200,"["+ROW+"]","1");
		assertThatThrownBy(()->client.list(new ReservationListQuery(status,from,to,null,null,null,null))).isInstanceOf(CatedraProtocolException.class);
	}
	@Test void acceptsInclusiveDateBoundariesAndExactPatientId() throws Exception {
		var client=start(200,"["+ROW+"]","1");
		var date=LocalDate.of(2026,12,1);
		assertThat(client.list(new ReservationListQuery(TechnicalReservation.Status.PHONE_PENDING,date,date,"user-7",null,null,null)).reservations()).hasSize(1);
	}

	private CatedraReservationListingClient start(int status,String body,String count) throws Exception {
		server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
		server.createContext("/",exchange->{
			requests++; target.set(exchange.getRequestURI().toASCIIString());
			authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
			exchange.getResponseHeaders().set("Content-Type",status==200?"application/json":"application/problem+json");
			if(count!=null) { exchange.getResponseHeaders().set("X-Total-Count",count); }
			exchange.getResponseHeaders().add("Link","<https://untrusted.example/next>; rel=\"next\"");
			var bytes=body.getBytes(StandardCharsets.UTF_8); exchange.sendResponseHeaders(status,bytes.length);
			try(var output=exchange.getResponseBody()) { output.write(bytes); }
		});
		server.start();
		var properties=new CatedraIntegrationProperties("test-group","test-private-token",
				new CatedraIntegrationProperties.RestProperties("http://127.0.0.1:"+server.getAddress().getPort()+"/base",Duration.ofSeconds(2),Duration.ofSeconds(2)),
				new CatedraIntegrationProperties.KafkaProperties("example:9092","group","actions","phone"));
		return new CatedraReservationListingClient(new CatedraRestClientConfiguration().catedraRestClient(properties,new JsonMapper()));
	}
}
