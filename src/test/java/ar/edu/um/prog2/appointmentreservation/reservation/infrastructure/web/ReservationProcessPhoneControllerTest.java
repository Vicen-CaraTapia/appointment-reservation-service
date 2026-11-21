package ar.edu.um.prog2.appointmentreservation.reservation.infrastructure.web;

import java.time.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.*;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.*;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.*;
import ar.edu.um.prog2.appointmentreservation.reservation.application.exception.*;
import ar.edu.um.prog2.appointmentreservation.reservation.application.model.*;
import ar.edu.um.prog2.appointmentreservation.reservation.application.port.out.*;
import ar.edu.um.prog2.appointmentreservation.reservation.application.usecase.*;
import ar.edu.um.prog2.appointmentreservation.reservation.domain.model.ReservationProcessStatus;
import ar.edu.um.prog2.appointmentreservation.reservation.infrastructure.security.SpringCurrentReservationUser;
import ar.edu.um.prog2.appointmentreservation.reservation.infrastructure.web.dto.SubmitPhoneRequest;
import ar.edu.um.prog2.appointmentreservation.support.FinalUserJwtTestSupport;
import ar.edu.um.prog2.appointmentreservation.user.infrastructure.security.*;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = ReservationProcessPhoneController.class, excludeAutoConfiguration = UserDetailsServiceAutoConfiguration.class,
		properties = "spring.jackson.deserialization.fail-on-unknown-properties=false")
@Import({FinalUserHttpSecurityConfiguration.class, ReservationProcessPhoneControllerTest.Settings.class})
@ExtendWith(OutputCaptureExtension.class)
class ReservationProcessPhoneControllerTest {
	private static final String SECRET = FinalUserJwtTestSupport.generateSecret();
	private static final String PHONE = "00000001234";
	private static final String PATH = "/api/reservation-processes/9/phone";
	private static final Instant NOW = Instant.parse("2030-01-01T12:00:00Z");
	private static final AuthenticatedReservationUser OWNER = new AuthenticatedReservationUser(42, "alice");
	@Autowired private MockMvc mvc;
	@Autowired private JsonMapper json;
	@MockitoBean private ReservationProcessStore processes;
	@MockitoBean private PhoneSubmissionStore phones;
	@MockitoBean private PhoneSubmissionPublisher publisher;
	private final AtomicReference<PhoneSubmissionIntent> prepared = new AtomicReference<>();
	private final List<String> tokens = new ArrayList<>();

	@BeforeEach
	void durableBoundariesAreControlled() {
		when(phones.prepare(eq(OWNER), any(), eq(NOW))).thenAnswer(call -> {
			NewPhoneSubmission command = call.getArgument(1);
			var intent = new PhoneSubmissionIntent(command.eventId(), command.localProcessId(), "original-request", "private-process",
					"private-hold", command.technicalGroupId(), command.normalizedPhone(), NOW, PhoneSubmissionIntent.State.PENDING);
			prepared.set(intent); return intent;
		});
		when(phones.claimPublication(anyString(), eq(NOW), any(), eq(3)))
				.thenAnswer(call -> Optional.of(new PhonePublicationClaim(UUID.randomUUID(), prepared.get())));
		when(publisher.publish(any())).thenReturn(CompletableFuture.completedFuture(null));
		observe(ReservationProcessStatus.WAITING_FOR_CONFIRMATION, null);
	}
	@AfterEach
	void noPrivateDataInLogs(CapturedOutput output) {
		assertThat(output.getAll()).doesNotContain(PHONE, SECRET, "private-rejected-input", "private-broker-diagnostic");
		for (String token : tokens) { assertThat(output.getAll()).doesNotContain(token); }
	}

	@Test
	void deserializesNormalizesAndPublishesThenReadsCurrentProgress() throws Exception {
		var result = send("{\"phoneNumber\":\"00 (000) 001-234\"}").andExpect(status().isAccepted())
				.andExpect(jsonPath("$.status").value("WAITING_FOR_CONFIRMATION"))
				.andExpect(jsonPath("$.requiredAction").value("NONE"))
				.andExpect(header().string("Cache-Control", "no-store")).andExpect(header().doesNotExist("Set-Cookie")).andReturn();
		assertThat(prepared.get().normalizedPhone()).isEqualTo(PHONE);
		assertThat(UUID.fromString(prepared.get().eventId())).isNotNull();
		assertThat(prepared.get().requestEventId()).isEqualTo("original-request");
		assertThat(json.readTree(result.getResponse().getContentAsString()).size()).isEqualTo(5);
		assertThat(result.getResponse().getContentAsString()).doesNotContain(PHONE, "private-process", "private-hold", "original-request", "test-group");
		var order = inOrder(publisher, processes);
		order.verify(publisher).publish(any());
		order.verify(processes).findOwnedProgress(9, OWNER);
	}
	@ParameterizedTest
	@EnumSource(value = ReservationProcessStatus.class, names = {"CONFIRMED", "EXPIRED", "INVALID"})
	void finalEventReceivedDuringPublicationIsReturnedWithoutRegression(ReservationProcessStatus finalState) throws Exception {
		when(publisher.publish(any())).thenAnswer(call -> { observe(finalState, null); return CompletableFuture.completedFuture(null); });
		send(body(PHONE)).andExpect(status().isAccepted()).andExpect(jsonPath("$.status").value(finalState.name()))
				.andExpect(jsonPath("$.requiredAction").value("NONE"));
	}
	@Test
	void uncertainBrokerResultKeepsTheDurablePendingObservation() throws Exception {
		when(publisher.publish(any())).thenReturn(CompletableFuture.failedFuture(new IllegalStateException("private-broker-diagnostic")));
		observe(ReservationProcessStatus.PHONE_SUBMISSION_PENDING, null);
		send(body(PHONE)).andExpect(status().isAccepted()).andExpect(jsonPath("$.status").value("PHONE_SUBMISSION_PENDING"));
		verify(phones).deferPublication(any(), any(), eq(NOW.plusSeconds(5)));
		verify(phones, never()).completePublication(any(), any(), any());
	}
	@ParameterizedTest
	@ValueSource(strings = {"userId", "ownerId", "externalPatientId", "groupId", "eventId", "requestEventId", "holdId",
		"reservationProcessId", "authorities", "status", "unknown"})
	void rejectsTechnicalAdministrativeAndUnknownFieldsWithTheMvcObjectMapper(String field) throws Exception {
		assertFailure(send("{\"phoneNumber\":\"" + PHONE + "\",\"" + field + "\":\"private-rejected-input\"}"), 400, "INVALID_REQUEST");
		verifyNoInteractions(phones, publisher, processes);
	}
	@ParameterizedTest
	@ValueSource(strings = {"{}", "{\"phoneNumber\":null}", "{\"phoneNumber\":\"\"}", "{\"phoneNumber\":\"   \"}"})
	void requiresANonblankPhone(String payload) throws Exception {
		assertFailure(send(payload), 400, "VALIDATION_ERROR");
		verifyNoInteractions(phones, publisher, processes);
	}
	@ParameterizedTest
	@ValueSource(strings = {"{\"phoneNumber\":1234567}", "{\"phoneNumber\":true}", "{\"phoneNumber\":[]}", "{\"phoneNumber\":{}}", "[]", "null", "{"})
	void requiresAJsonObjectAndAStringWithoutCoercion(String payload) throws Exception {
		assertFailure(send(payload), 400, "INVALID_REQUEST");
		verifyNoInteractions(phones, publisher, processes);
	}
	@ParameterizedTest
	@ValueSource(strings = {"123456", "1234567890123456", "private-rejected-input", "12+34567"})
	void invalidNormalizedPhoneCannotReachPersistenceOrKafka(String input) throws Exception {
		assertFailure(send(body(input)), 400, "INVALID_PHONE_NUMBER");
		verifyNoInteractions(phones, publisher, processes);
	}
	@ParameterizedTest
	@EnumSource(PhoneSubmissionStateException.Reason.class)
	void mapsControlledOwnershipConflictExpiryAndStorageOutcomes(PhoneSubmissionStateException.Reason reason) throws Exception {
		when(phones.prepare(eq(OWNER), any(), any())).thenThrow(new PhoneSubmissionStateException(reason));
		int status = switch (reason) { case NOT_FOUND -> 404; case CONFLICT, EXPIRED -> 409; case STORAGE_FAILURE -> 503; };
		String code = switch (reason) {
			case NOT_FOUND -> "RESERVATION_PROCESS_NOT_FOUND";
			case CONFLICT -> "PHONE_SUBMISSION_CONFLICT";
			case EXPIRED -> "RESERVATION_PROCESS_EXPIRED";
			case STORAGE_FAILURE -> "PHONE_SUBMISSION_OUTCOME_UNKNOWN";
		};
		assertFailure(send(body(PHONE)), status, code);
		verifyNoInteractions(publisher, processes);
		verify(phones, never()).claimPublication(any(), any(), any(), anyInt());
	}
	@ParameterizedTest
	@ValueSource(strings = {"0", "-1", "abc"})
	void rejectsInvalidLocalIds(String id) throws Exception {
		assertFailure(mvc.perform(post("/api/reservation-processes/" + id + "/phone").header("Authorization", "Bearer " + token(42, SECRET))
				.contentType("application/json").content(body(PHONE))), 400, "INVALID_REQUEST");
		verifyNoInteractions(phones, publisher, processes);
	}
	@Test
	void foreignOwnerIsResolvedFromJwtAndCannotPublish() throws Exception {
		var foreign = new AuthenticatedReservationUser(43, "alice");
		when(phones.prepare(eq(foreign), any(), any())).thenThrow(new PhoneSubmissionStateException(PhoneSubmissionStateException.Reason.NOT_FOUND));
		assertFailure(mvc.perform(post(PATH).header("Authorization", "Bearer " + token(43, SECRET)).contentType("application/json").content(body(PHONE))),
				404, "RESERVATION_PROCESS_NOT_FOUND");
		verifyNoInteractions(publisher, processes);
	}
	@ParameterizedTest
	@ValueSource(booleans = {true, false})
	void missingOrIncorrectlySignedBearerCannotReachTheUseCase(boolean missing) throws Exception {
		var request = post(PATH).contentType("application/json").content(body(PHONE));
		if (!missing) { request.header("Authorization", "Bearer " + token(42, FinalUserJwtTestSupport.generateSecret())); }
		assertFailure(mvc.perform(request), 401, "UNAUTHORIZED");
		verifyNoInteractions(phones, publisher, processes);
	}
	@Test
	void repeatedSubmissionUsesTheExistingIntentWithoutAnotherPublication() throws Exception {
		send(body(PHONE)).andExpect(status().isAccepted());
		var original = prepared.get();
		when(phones.prepare(eq(OWNER), any(), any())).thenReturn(original);
		when(phones.claimPublication(eq(original.eventId()), any(), any(), anyInt())).thenReturn(Optional.empty());
		send("{\"phoneNumber\":\"00 (000) 001-234\"}").andExpect(status().isAccepted());
		verify(publisher, times(1)).publish(original);
	}

	@Test
	void rejectionDuringPublicationReturnsTheCorrectableAction() throws Exception {
		when(publisher.publish(any())).thenAnswer(call -> {
			observe(ReservationProcessStatus.WAITING_FOR_PHONE, "INVALID_PHONE_NUMBER");
			return CompletableFuture.completedFuture(null);
		});
		send(body(PHONE)).andExpect(status().isAccepted()).andExpect(jsonPath("$.status").value("WAITING_FOR_PHONE"))
				.andExpect(jsonPath("$.requiredAction").value("CORRECT_PHONE"))
				.andExpect(jsonPath("$.reason").value("INVALID_PHONE_NUMBER"));
	}

	@Test
	void phoneInputIsRedactedAndNeverSerialized() {
		var input = new SubmitPhoneRequest(PHONE);
		assertThat(input.toString()).doesNotContain(PHONE);
		assertThat(json.writeValueAsString(input)).doesNotContain(PHONE, "phoneNumber");
	}

	private void observe(ReservationProcessStatus status, String reason) {
		when(processes.findOwnedProgress(9, OWNER)).thenReturn(Optional.of(new StoredReservationProgress(9, status, NOW.plusSeconds(60), true, reason)));
	}
	private ResultActions send(String payload) throws Exception {
		return mvc.perform(post(PATH).header("Authorization", "Bearer " + token(42, SECRET)).contentType("application/json").content(payload));
	}
	private void assertFailure(ResultActions result, int statusCode, String code) throws Exception {
		var response = result.andExpect(status().is(statusCode)).andExpect(jsonPath("$.code").value(code))
				.andExpect(content().contentTypeCompatibleWith("application/problem+json"))
				.andExpect(header().string("Cache-Control", "no-store")).andReturn().getResponse();
		assertThat(response.getContentAsString()).doesNotContain(PHONE, SECRET, "private-rejected-input", "private-broker-diagnostic");
	}
	private static String body(String phone) { return "{\"phoneNumber\":\"" + phone + "\"}"; }
	private String token(long owner, String key) {
		var properties = new FinalUserJwtProperties(); properties.setBase64Secret(key);
		String token = new NimbusFinalUserTokenIssuer(properties, Clock.systemUTC()).issue(new FinalUserPrincipal(owner, "alice", "test-hash", true,
				List.of(new SimpleGrantedAuthority("ROLE_USER"))), false).value();
		tokens.add(token); return token;
	}
	@TestConfiguration(proxyBeanMethods = false)
	static class Settings {
		@Bean JwtDecoder decoder() {
			var properties = new FinalUserJwtProperties(); properties.setBase64Secret(SECRET);
			return new FinalUserSecurityConfiguration().finalUserJwtDecoder(properties);
		}
		@Bean PreparePhoneSubmissionUseCase prepare(PhoneSubmissionStore store) {
			return new PreparePhoneSubmissionUseCase(new SpringCurrentReservationUser(), store, "test-group", Clock.fixed(NOW, ZoneOffset.UTC));
		}
		@Bean SubmitPhoneInformationUseCase submit(PreparePhoneSubmissionUseCase prepare, PhoneSubmissionStore store, PhoneSubmissionPublisher publisher) {
			return new SubmitPhoneInformationUseCase(prepare, store, publisher, Clock.fixed(NOW, ZoneOffset.UTC), 3,
					Duration.ofMinutes(3), Duration.ofSeconds(1), Duration.ofSeconds(5));
		}
		@Bean GetReservationProcessProgressUseCase progress(ReservationProcessStore store) {
			return new GetReservationProcessProgressUseCase(new SpringCurrentReservationUser(), store, Clock.fixed(NOW, ZoneOffset.UTC));
		}
	}
}
