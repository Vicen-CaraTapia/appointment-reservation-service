package ar.edu.um.prog2.appointmentreservation.reservation.infrastructure.web;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import ar.edu.um.prog2.appointmentreservation.availability.application.exception.InvalidAvailabilityScheduleException;
import ar.edu.um.prog2.appointmentreservation.professional.application.exception.ProfessionalLookupException;
import ar.edu.um.prog2.appointmentreservation.reservation.application.exception.InitialConfirmationException;
import ar.edu.um.prog2.appointmentreservation.reservation.application.exception.InvalidReservationSlotException;
import ar.edu.um.prog2.appointmentreservation.reservation.application.exception.ReservationAuthenticationRequiredException;
import ar.edu.um.prog2.appointmentreservation.reservation.application.exception.ReservationProcessStateException;
import ar.edu.um.prog2.appointmentreservation.reservation.application.model.AppointmentHold;
import ar.edu.um.prog2.appointmentreservation.reservation.application.model.AuthenticatedReservationUser;
import ar.edu.um.prog2.appointmentreservation.reservation.application.model.ConfirmationPatient;
import ar.edu.um.prog2.appointmentreservation.reservation.application.model.InitialReservationConfirmation;
import ar.edu.um.prog2.appointmentreservation.reservation.application.model.StartReservationCommand;
import ar.edu.um.prog2.appointmentreservation.reservation.application.model.StoredReservationProcess;
import ar.edu.um.prog2.appointmentreservation.reservation.application.port.out.AppointmentHoldConfirmer;
import ar.edu.um.prog2.appointmentreservation.reservation.application.port.out.ReservationProcessStore;
import ar.edu.um.prog2.appointmentreservation.reservation.application.service.ReservationSlotValidator;
import ar.edu.um.prog2.appointmentreservation.reservation.application.usecase.ConfirmReservationProcessUseCase;
import ar.edu.um.prog2.appointmentreservation.reservation.domain.model.ReservationProcessStatus;
import ar.edu.um.prog2.appointmentreservation.reservation.infrastructure.security.SpringCurrentReservationUser;
import ar.edu.um.prog2.appointmentreservation.support.FinalUserJwtTestSupport;
import ar.edu.um.prog2.appointmentreservation.user.infrastructure.security.FinalUserHttpSecurityConfiguration;
import ar.edu.um.prog2.appointmentreservation.user.infrastructure.security.FinalUserJwtProperties;
import ar.edu.um.prog2.appointmentreservation.user.infrastructure.security.FinalUserPrincipal;
import ar.edu.um.prog2.appointmentreservation.user.infrastructure.security.FinalUserSecurityConfiguration;
import ar.edu.um.prog2.appointmentreservation.user.infrastructure.security.NimbusFinalUserTokenIssuer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = ReservationProcessConfirmationController.class,
		excludeAutoConfiguration = UserDetailsServiceAutoConfiguration.class,
		properties = "spring.jackson.deserialization.fail-on-unknown-properties=false")
@Import({FinalUserHttpSecurityConfiguration.class, ReservationProcessConfirmationControllerTest.JwtTestConfiguration.class})
@ExtendWith(OutputCaptureExtension.class)
class ReservationProcessConfirmationControllerTest {

	private static final String PATH = "/api/reservation-processes/123/confirm";
	private static final String SECRET = FinalUserJwtTestSupport.generateSecret();
	private static final String PRIVATE_INPUT = "private-test-input-that-must-not-leak";
	private static final String JSON = "{\"patientFirstName\":\"Juan\",\"patientLastName\":\"Perez\"}";
	private static final ConfirmationPatient PATIENT = new ConfirmationPatient("Juan", "Perez");
	private static final AppointmentHold HOLD = new AppointmentHold("private-hold", "private-process",
			Instant.parse("2026-11-19T18:30:00.123456Z"));
	private static final StartReservationCommand SELECTION = new StartReservationCommand(202,
			LocalDate.of(2026, 11, 19), LocalTime.of(18, 0));
	private final List<String> issuedTokens = new ArrayList<>();
	@Autowired private MockMvc mvc;
	@MockitoBean private ConfirmReservationProcessUseCase confirmReservation;

	@AfterEach
	void noSecretsOrRejectedValuesInLogs(CapturedOutput output) {
		assertThat(output.getAll()).doesNotContain(SECRET, PRIVATE_INPUT);
		for (String token : issuedTokens) {
			assertThat(output.getAll()).doesNotContain(token);
		}
	}

	@ParameterizedTest
	@EnumSource(value = ReservationProcessStatus.class, names = {"WAITING_FOR_PHONE", "CONFIRMATION_PENDING"})
	void returnsOnlyTheObservedLocalStateWithNoSecretsOrSession(ReservationProcessStatus observed) throws Exception {
		when(confirmReservation.confirm(123, PATIENT)).thenReturn(process(observed));
		String token = validToken();
		String body = mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + token).content(JSON))
				.andExpect(status().isAccepted())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
				.andExpect(jsonPath("$.id").value(123)).andExpect(jsonPath("$.status").value(observed.name()))
				.andExpect(jsonPath("$.expiresAt").value(HOLD.expiresAt().toString()))
				.andExpect(jsonPath("$.*").value(org.hamcrest.Matchers.hasSize(3)))
				.andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
				.andExpect(header().doesNotExist(HttpHeaders.AUTHORIZATION))
				.andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE))
				.andExpect(result -> assertThat(result.getRequest().getSession(false)).isNull())
				.andReturn().getResponse().getContentAsString();
		assertThat(body).doesNotContain(token, SECRET, "Juan", "Perez", HOLD.holdId(), HOLD.reservationProcessId(),
				"externalPatientId", "userId", "owner", "version", "CONFIRMED");
		verify(confirmReservation).confirm(123, PATIENT);
		verifyNoMoreInteractions(confirmReservation);
	}

	@Test
	void patientWhitespaceAndCaseReachTheUseCaseUnchanged() throws Exception {
		var patient = new ConfirmationPatient(" JuAn ", " PéRez ");
		when(confirmReservation.confirm(123, patient)).thenReturn(process(ReservationProcessStatus.WAITING_FOR_PHONE));
		perform(PATH, """
				{"patientFirstName":" JuAn ","patientLastName":" PéRez "}
				""")
				.andExpect(status().isAccepted());
		verify(confirmReservation).confirm(123, patient);
	}

	@ParameterizedTest
	@EnumSource(value = ReservationProcessStatus.class, names = {"WAITING_FOR_PHONE", "CONFIRMATION_PENDING"})
	void repeatedRequestsUseTheAuthenticatedOwnerAndNeverRepeatARemotePost(ReservationProcessStatus observed) throws Exception {
		var store = mock(ReservationProcessStore.class);
		var slots = mock(ReservationSlotValidator.class);
		var remote = mock(AppointmentHoldConfirmer.class);
		var owner = new AuthenticatedReservationUser(42, "alice");
		when(store.findOwned(123, owner)).thenReturn(Optional.of(process(observed)));
		var useCase = new ConfirmReservationProcessUseCase(new SpringCurrentReservationUser(), store, slots, remote, Clock.systemUTC());
		when(confirmReservation.confirm(anyLong(), any())).thenAnswer(call -> useCase.confirm(call.getArgument(0), call.getArgument(1)));
		for (int click = 0; click < 2; click++) {
			perform(PATH, JSON).andExpect(status().isAccepted()).andExpect(jsonPath("$.status").value(observed.name()));
		}
		verify(store, org.mockito.Mockito.times(2)).findOwned(123, owner);
		verifyNoInteractions(slots, remote);
		verifyNoMoreInteractions(store);
	}

	@Test
	void positiveInt64LocalIdIsNotTruncated() throws Exception {
		var result = new StoredReservationProcess(Long.MAX_VALUE, 2, ReservationProcessStatus.WAITING_FOR_PHONE,
				SELECTION, "user-42", HOLD, PATIENT.firstName(), PATIENT.lastName());
		when(confirmReservation.confirm(Long.MAX_VALUE, PATIENT)).thenReturn(result);
		perform("/api/reservation-processes/" + Long.MAX_VALUE + "/confirm", JSON)
				.andExpect(status().isAccepted()).andExpect(jsonPath("$.id").value(Long.MAX_VALUE));
		verify(confirmReservation).confirm(Long.MAX_VALUE, PATIENT);
	}

	@Test
	void realUseCaseConfirmsOnlyTheJwtOwnersProcessAndReturnsThePersistedExpiration() throws Exception {
		var store = mock(ReservationProcessStore.class);
		var slots = mock(ReservationSlotValidator.class);
		var remote = mock(AppointmentHoldConfirmer.class);
		var owner = new AuthenticatedReservationUser(42, "alice");
		Instant now = Instant.parse("2026-11-19T18:00:00Z");
		var held = new StoredReservationProcess(123, 0, ReservationProcessStatus.HELD, SELECTION, "user-42", HOLD, null, null);
		var claim = new StoredReservationProcess(123, 1, ReservationProcessStatus.CONFIRMATION_PENDING,
				SELECTION, "user-42", HOLD, PATIENT.firstName(), PATIENT.lastName());
		var accepted = new InitialReservationConfirmation(HOLD.reservationProcessId(), PATIENT.firstName(),
				PATIENT.lastName(), Instant.parse("2026-11-19T18:40:00.987654Z"));
		var saved = new StoredReservationProcess(123, 2, ReservationProcessStatus.WAITING_FOR_PHONE, SELECTION, "user-42",
				new AppointmentHold(HOLD.holdId(), HOLD.reservationProcessId(), accepted.expiresAt()), PATIENT.firstName(), PATIENT.lastName());
		when(store.findOwned(123, owner)).thenReturn(Optional.of(held));
		when(store.claimInitialConfirmation(123, 0, owner, PATIENT, now)).thenReturn(claim);
		when(remote.confirm(HOLD, "user-42", PATIENT)).thenReturn(accepted);
		when(store.recordInitialConfirmation(123, 1, owner, accepted)).thenReturn(saved);
		var useCase = new ConfirmReservationProcessUseCase(new SpringCurrentReservationUser(), store, slots, remote,
				Clock.fixed(now, ZoneOffset.UTC));
		when(confirmReservation.confirm(anyLong(), any())).thenAnswer(call -> useCase.confirm(call.getArgument(0), call.getArgument(1)));
		perform(PATH + "?userId=99&ownerUserId=99&externalPatientId=user-99", JSON)
				.andExpect(status().isAccepted()).andExpect(jsonPath("$.status").value("WAITING_FOR_PHONE"))
				.andExpect(jsonPath("$.expiresAt").value(accepted.expiresAt().toString()));
		verify(store).findOwned(123, owner);
		verify(store).claimInitialConfirmation(123, 0, owner, PATIENT, now);
		verify(store).recordInitialConfirmation(123, 1, owner, accepted);
		verify(remote).confirm(HOLD, "user-42", PATIENT);
		verify(slots).validate(SELECTION);
		verifyNoMoreInteractions(store, slots, remote);
	}

	@ParameterizedTest
	@ValueSource(strings = {"missing", "malformed", "wrong-key", "expired", "technical", "internal", "hs256"})
	void invalidOrOtherIdentityTokensNeverInvokeConfirmation(String scenario) throws Exception {
		String token = switch (scenario) {
			case "missing" -> "";
			case "malformed" -> PRIVATE_INPUT;
			case "wrong-key" -> token(FinalUserJwtTestSupport.generateSecret(), Clock.systemUTC());
			case "expired" -> token(SECRET, Clock.fixed(Instant.now().minus(Duration.ofHours(2)), ZoneOffset.UTC));
			case "technical" -> alternateToken("catedra", "technical-api", JWSAlgorithm.HS512);
			case "internal" -> alternateToken(FinalUserJwtProperties.ISSUER, "catalog-sync-service", JWSAlgorithm.HS512);
			default -> alternateToken(FinalUserJwtProperties.ISSUER, FinalUserJwtProperties.AUDIENCE, JWSAlgorithm.HS256);
		};
		var request = post(PATH).contentType(MediaType.APPLICATION_JSON).content(JSON);
		if (!token.isEmpty()) { request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token); }
		mvc.perform(request).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
				.andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"))
				.andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"));
		verifyNoInteractions(confirmReservation);
	}

	@ParameterizedTest
	@ValueSource(strings = {"0", "-1", "9223372036854775808", "1.5", "not-a-local-id"})
	void invalidLocalIdDoesNotInvokeTheUseCase(String id) throws Exception {
		perform("/api/reservation-processes/" + id + "/confirm", JSON)
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
		verifyNoInteractions(confirmReservation);
	}

	@ParameterizedTest
	@ValueSource(strings = {"{}", "{\"patientFirstName\":null,\"patientLastName\":null}",
			"{\"patientFirstName\":\" \",\"patientLastName\":\"Perez\"}",
			"{\"patientFirstName\":\"J\",\"patientLastName\":\"Perez\"}",
			"{\"patientFirstName\":\"Juan\"}", "{\"patientLastName\":\"Perez\"}"})
	void invalidPatientFieldsAreRejectedBeforeUseCase(String json) throws Exception {
		perform(PATH, json).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
		verifyNoInteractions(confirmReservation);
	}

	@ParameterizedTest
	@ValueSource(strings = {"", "null", "[]", "{broken", "{\"patientFirstName\":42,\"patientLastName\":\"Perez\"}",
			"{\"patientFirstName\":true,\"patientLastName\":\"Perez\"}",
			"{\"patientFirstName\":{},\"patientLastName\":\"Perez\"}"})
	void invalidJsonIsRejectedBeforeUseCase(String json) throws Exception {
		perform(PATH, json).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
		verifyNoInteractions(confirmReservation);
	}

	@ParameterizedTest
	@ValueSource(strings = {"userId", "ownerUserId", "owner", "externalPatientId", "holdId", "reservationProcessId",
			"id", "status", "expiresAt", "version", "createdBy", "lastModifiedBy", "unknown"})
	void unknownAndAdministrativeFieldsAreRejectedEvenWithPermissiveMapper(String field) throws Exception {
		perform(PATH, JSON.substring(0, JSON.length() - 1) + ",\"" + field + "\":\"" + PRIVATE_INPUT + "\"}")
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
		verifyNoInteractions(confirmReservation);
	}

	@Test
	void oversizedSensitivePatientInputDoesNotAppearInTheResponse() throws Exception {
		String body = perform(PATH, "{\"patientFirstName\":\"" + PRIVATE_INPUT.repeat(4) + "\",\"patientLastName\":\"Perez\"}")
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
				.andReturn().getResponse().getContentAsString();
		assertThat(body).doesNotContain(PRIVATE_INPUT);
		verifyNoInteractions(confirmReservation);
	}

	@Test
	void authenticatedIdentityComesFromTheJwtAndForeignAndAbsentProcessesAreIndistinguishable() throws Exception {
		var store = mock(ReservationProcessStore.class);
		var slots = mock(ReservationSlotValidator.class);
		var remote = mock(AppointmentHoldConfirmer.class);
		var owner = new AuthenticatedReservationUser(42, "alice");
		when(store.findOwned(123, owner)).thenReturn(Optional.empty());
		when(store.findOwned(999, owner)).thenReturn(Optional.empty());
		var useCase = new ConfirmReservationProcessUseCase(new SpringCurrentReservationUser(), store, slots, remote, Clock.systemUTC());
		when(confirmReservation.confirm(anyLong(), any())).thenAnswer(call -> useCase.confirm(call.getArgument(0), call.getArgument(1)));
		String foreign = perform(PATH + "?userId=99&ownerUserId=99&externalPatientId=user-99", JSON)
				.andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("RESERVATION_PROCESS_NOT_FOUND"))
				.andReturn().getResponse().getContentAsString();
		String absent = perform("/api/reservation-processes/999/confirm", JSON)
				.andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("RESERVATION_PROCESS_NOT_FOUND"))
				.andReturn().getResponse().getContentAsString();
		// Only instance (the requested URI) may differ, never an owner's identity or resource details.
		assertThat(foreign.replace("/123/", "/999/")).isEqualTo(absent);
		verify(store).findOwned(123, owner);
		verify(store).findOwned(999, owner);
		verifyNoInteractions(slots, remote);
		verifyNoMoreInteractions(store);
	}

	@ParameterizedTest
	@MethodSource("initialFailures")
	void remoteErrorsFollowTheApprovedStatusCodeMatrix(InitialConfirmationException.Reason reason, int status, String code) throws Exception {
		when(confirmReservation.confirm(123, PATIENT)).thenThrow(new InitialConfirmationException(reason));
		String body = assertFailure(status, code);
		if (reason == InitialConfirmationException.Reason.TIMEOUT || reason == InitialConfirmationException.Reason.UNAVAILABLE
				|| reason == InitialConfirmationException.Reason.INVALID_RESPONSE || reason == InitialConfirmationException.Reason.LOCAL_STATE_FAILURE
				|| reason == InitialConfirmationException.Reason.RESERVATION_PROCESS_ALREADY_CONFIRMED) {
			assertThat(body).contains("Do not automatically repeat");
		}
	}

	static Stream<Arguments> initialFailures() {
		return Stream.of(
				Arguments.of(InitialConfirmationException.Reason.PROFESSIONAL_NOT_FOUND, 404, "PROFESSIONAL_NOT_FOUND"),
				Arguments.of(InitialConfirmationException.Reason.INVALID_SLOT, 400, "INVALID_SLOT"),
				Arguments.of(InitialConfirmationException.Reason.SLOT_ALREADY_RESERVED, 409, "SLOT_ALREADY_RESERVED"),
				Arguments.of(InitialConfirmationException.Reason.HOLD_NOT_FOUND, 404, "HOLD_NOT_FOUND"),
				Arguments.of(InitialConfirmationException.Reason.HOLD_EXPIRED, 404, "HOLD_EXPIRED"),
				Arguments.of(InitialConfirmationException.Reason.HOLD_OWNER_MISMATCH, 502, "HOLD_OWNER_MISMATCH"),
				Arguments.of(InitialConfirmationException.Reason.EXTERNAL_PATIENT_MISMATCH, 502, "EXTERNAL_PATIENT_MISMATCH"),
				Arguments.of(InitialConfirmationException.Reason.RESERVATION_PROCESS_ALREADY_CONFIRMED, 409, "RESERVATION_PROCESS_ALREADY_CONFIRMED"),
				Arguments.of(InitialConfirmationException.Reason.TIMEOUT, 504, "CONFIRMATION_OUTCOME_UNKNOWN"),
				Arguments.of(InitialConfirmationException.Reason.UNAVAILABLE, 503, "CONFIRMATION_OUTCOME_UNKNOWN"),
				Arguments.of(InitialConfirmationException.Reason.INVALID_RESPONSE, 502, "CONFIRMATION_OUTCOME_UNKNOWN"),
				Arguments.of(InitialConfirmationException.Reason.UNAUTHORIZED, 502, "RESERVATION_DEPENDENCY_ERROR"),
				Arguments.of(InitialConfirmationException.Reason.FORBIDDEN, 502, "RESERVATION_DEPENDENCY_ERROR"),
				Arguments.of(InitialConfirmationException.Reason.REQUEST_REJECTED, 502, "RESERVATION_DEPENDENCY_ERROR"),
				Arguments.of(InitialConfirmationException.Reason.LOCAL_STATE_FAILURE, 500, "CONFIRMATION_PERSISTENCE_FAILED"));
	}

	@ParameterizedTest
	@MethodSource("stateFailures")
	void localStateErrorsHaveStableSafeCodes(ReservationProcessStateException.Reason reason, int status, String code) throws Exception {
		when(confirmReservation.confirm(123, PATIENT)).thenThrow(new ReservationProcessStateException(reason));
		assertFailure(status, code);
	}

	static Stream<Arguments> stateFailures() {
		return Stream.of(
				Arguments.of(ReservationProcessStateException.Reason.NOT_FOUND, 404, "RESERVATION_PROCESS_NOT_FOUND"),
				Arguments.of(ReservationProcessStateException.Reason.CONFLICT, 409, "RESERVATION_PROCESS_CONFLICT"),
				Arguments.of(ReservationProcessStateException.Reason.EXPIRED, 404, "HOLD_EXPIRED"),
				Arguments.of(ReservationProcessStateException.Reason.STORAGE_FAILURE, 500, "RESERVATION_PERSISTENCE_FAILED"));
	}

	@ParameterizedTest
	@MethodSource("catalogFailures")
	void catalogErrorsDoNotBecomeUserAuthenticationFailures(ProfessionalLookupException.Reason reason, int status, String code) throws Exception {
		when(confirmReservation.confirm(123, PATIENT)).thenThrow(new ProfessionalLookupException(reason));
		assertFailure(status, code);
	}

	static Stream<Arguments> catalogFailures() {
		return Stream.of(
				Arguments.of(ProfessionalLookupException.Reason.NOT_FOUND, 404, "PROFESSIONAL_NOT_FOUND"),
				Arguments.of(ProfessionalLookupException.Reason.DISABLED, 400, "PROFESSIONAL_DISABLED"),
				Arguments.of(ProfessionalLookupException.Reason.CATALOG_TIMEOUT, 504, "RESERVATION_DEPENDENCY_TIMEOUT"),
				Arguments.of(ProfessionalLookupException.Reason.CATALOG_UNAVAILABLE, 503, "RESERVATION_DEPENDENCY_UNAVAILABLE"),
				Arguments.of(ProfessionalLookupException.Reason.CATALOG_UNAUTHORIZED, 502, "RESERVATION_DEPENDENCY_ERROR"),
				Arguments.of(ProfessionalLookupException.Reason.CATALOG_FORBIDDEN, 502, "RESERVATION_DEPENDENCY_ERROR"),
				Arguments.of(ProfessionalLookupException.Reason.INVALID_CATALOG_RESPONSE, 502, "RESERVATION_DEPENDENCY_ERROR"));
	}

	@Test
	void unexpectedFailureMessagesAndSqlOrRemoteCausesAreNeverReturnedOrLogged() throws Exception {
		when(confirmReservation.confirm(123, PATIENT)).thenThrow(new IllegalStateException(PRIVATE_INPUT,
				new IllegalArgumentException("SQL or remote cause: " + PRIVATE_INPUT)));
		assertFailure(500, "RESERVATION_CONFIRMATION_FAILED");
	}

	@Test
	void unavailableAuthenticatedIdentityIsStill401() throws Exception {
		when(confirmReservation.confirm(123, PATIENT)).thenThrow(new ReservationAuthenticationRequiredException());
		assertFailure(401, "UNAUTHORIZED");
	}

	@Test
	void invalidSelectionIsRejected() throws Exception {
		when(confirmReservation.confirm(123, PATIENT)).thenThrow(new InvalidReservationSlotException());
		assertFailure(400, "INVALID_SLOT");
	}

	@Test
	void invalidCatalogScheduleIsADependencyError() throws Exception {
		when(confirmReservation.confirm(123, PATIENT)).thenThrow(new InvalidAvailabilityScheduleException());
		assertFailure(502, "RESERVATION_DEPENDENCY_ERROR");
	}

	private String assertFailure(int expectedStatus, String code) throws Exception {
		var result = perform(PATH, JSON).andExpect(status().is(expectedStatus))
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.status").value(expectedStatus)).andExpect(jsonPath("$.code").value(code))
				.andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
				.andExpect(header().doesNotExist(HttpHeaders.AUTHORIZATION))
				.andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE))
				.andExpect(header().doesNotExist(HttpHeaders.RETRY_AFTER));
		if (expectedStatus == 401) { result.andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer")); }
		else { result.andExpect(header().doesNotExist(HttpHeaders.WWW_AUTHENTICATE)); }
		String body = result.andReturn().getResponse().getContentAsString();
		assertThat(body).doesNotContain(SECRET, PRIVATE_INPUT, HOLD.holdId(), HOLD.reservationProcessId(),
				"technicalJwt", "passwordHash", "Exception", "stackTrace");
		verify(confirmReservation).confirm(123, PATIENT);
		verifyNoMoreInteractions(confirmReservation);
		return body;
	}

	private ResultActions perform(String path, String json) throws Exception {
		return mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON)
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + validToken()).content(json));
	}

	private static StoredReservationProcess process(ReservationProcessStatus status) {
		return new StoredReservationProcess(123, 2, status, SELECTION, "user-42", HOLD, PATIENT.firstName(), PATIENT.lastName());
	}

	private String validToken() { return token(SECRET, Clock.systemUTC()); }

	private String token(String secret, Clock clock) {
		var properties = new FinalUserJwtProperties();
		properties.setBase64Secret(secret);
		String token = new NimbusFinalUserTokenIssuer(properties, clock).issue(new FinalUserPrincipal(42, "alice", "test-only-hash",
				true, List.of(new SimpleGrantedAuthority("ROLE_USER"))), false).value();
		issuedTokens.add(token);
		return token;
	}

	private String alternateToken(String issuer, String audience, JWSAlgorithm algorithm) throws Exception {
		var claims = new JWTClaimsSet.Builder().subject("alice").issuer(issuer).audience(audience)
				.claim("userId", 42L).claim("auth", "ROLE_USER").issueTime(new Date())
				.expirationTime(Date.from(Instant.now().plusSeconds(3600))).build();
		var jwt = new SignedJWT(new JWSHeader(algorithm), claims);
		jwt.sign(new MACSigner(Base64.getDecoder().decode(SECRET)));
		String token = jwt.serialize();
		issuedTokens.add(token);
		return token;
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
}
