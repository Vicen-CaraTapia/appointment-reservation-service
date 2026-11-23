package ar.edu.um.prog2.appointmentreservation.reservation.infrastructure.web;

import java.time.*;
import java.util.List;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;
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
import tools.jackson.databind.json.JsonMapper;
import ar.edu.um.prog2.appointmentreservation.reservation.application.exception.*;
import ar.edu.um.prog2.appointmentreservation.reservation.application.model.*;
import ar.edu.um.prog2.appointmentreservation.reservation.application.port.out.*;
import ar.edu.um.prog2.appointmentreservation.reservation.application.usecase.*;
import ar.edu.um.prog2.appointmentreservation.reservation.domain.model.ReservationProcessStatus;
import ar.edu.um.prog2.appointmentreservation.reservation.infrastructure.security.SpringCurrentReservationUser;
import ar.edu.um.prog2.appointmentreservation.reservation.infrastructure.web.dto.CancelReservationRequest;
import ar.edu.um.prog2.appointmentreservation.support.FinalUserJwtTestSupport;
import ar.edu.um.prog2.appointmentreservation.user.infrastructure.security.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = ReservationProcessCancellationController.class,
        excludeAutoConfiguration = UserDetailsServiceAutoConfiguration.class,
        properties = "spring.jackson.deserialization.fail-on-unknown-properties=false")
@Import({FinalUserHttpSecurityConfiguration.class, ReservationProcessCancellationControllerTest.Settings.class})
@ExtendWith(OutputCaptureExtension.class)
class ReservationProcessCancellationControllerTest {
    private static final String SECRET = FinalUserJwtTestSupport.generateSecret();
    private static final String PATH = "/api/reservation-processes/9/cancel";
    private static final Instant NOW = Instant.parse("2030-01-01T12:00:00Z");
    private static final AuthenticatedReservationUser OWNER = new AuthenticatedReservationUser(42,"alice");
    @Autowired MockMvc mvc;
    @Autowired JsonMapper json;
    @MockitoBean ReservationProcessStore store;
    @MockitoBean ReservationCanceller remote;
    @MockitoBean ListOwnedReservationsUseCase listing;

    @BeforeEach void setup() {
        when(store.prepareCancellation(eq(9L),eq(OWNER),nullable(String.class),eq(NOW))).thenAnswer(call ->
                new PreparedReservationCancellation(9,8251,"private-process",ReservationProcessStatus.CANCELLATION_PENDING,
                        call.getArgument(2),NOW,true));
        when(remote.cancel(eq("private-process"),nullable(String.class)))
                .thenReturn(new ReservationCancellation(8251,"private-process",NOW));
        when(store.recordCancellation(eq(9L),eq(OWNER),any())).thenReturn(new StoredReservationProcess(9,3,
                ReservationProcessStatus.CANCELLED,new StartReservationCommand(202,LocalDate.of(2030,1,2),LocalTime.NOON),
                "user-42",new AppointmentHold("private-hold","private-process",NOW.plusSeconds(60)),null,null));
    }
    @AfterEach void noSensitiveLogs(CapturedOutput output) {
        assertThat(output.getAll()).doesNotContain(SECRET,"private reason","private diagnostic");
    }
    @ParameterizedTest @ValueSource(strings={"", "{}", "{\"reason\":null}", "{\"reason\":\"private reason\"}"})
    void optionalReasonProducesMinimalVerifiedResponse(String body) throws Exception {
        var response=send(PATH,body,42).andExpect(status().isOk()).andExpect(jsonPath("$.id").value(9))
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(header().string("Cache-Control","no-store"))
                .andExpect(header().doesNotExist("Set-Cookie")).andReturn().getResponse().getContentAsString();
        assertThat(json.readTree(response).size()).isEqualTo(2);
        assertThat(response).doesNotContain("private", "user-42", "8251");
        verify(remote).cancel("private-process",body.contains("private reason")?"private reason":null);
    }
    @ParameterizedTest @ValueSource(strings={"userId","ownerId","externalPatientId","groupId","reservationProcessId","status","unknown"})
    void administrativeAndUnknownFieldsAreRejected(String field) throws Exception {
        failure(send(PATH,"{\""+field+"\":\"private reason\"}",42),400,"INVALID_REQUEST");
        verifyNoInteractions(store,remote,listing);
    }
    @ParameterizedTest @ValueSource(strings={"{\"reason\":123}","{\"reason\":true}","{\"reason\":[]}","{\"reason\":{}}","[]","{"})
    void malformedJsonAndCoercionAreRejected(String body) throws Exception {
        failure(send(PATH,body,42),400,"INVALID_REQUEST"); verifyNoInteractions(store,remote,listing);
    }
    @Test void reasonBoundaryIsEnforced() throws Exception {
        send(PATH,"{\"reason\":\""+"x".repeat(500)+"\"}",42).andExpect(status().isOk());
        clearInvocations(store,remote,listing);
        failure(send(PATH,"{\"reason\":\""+"x".repeat(501)+"\"}",42),400,"INVALID_REQUEST");
        verifyNoInteractions(store,remote,listing);
    }
    @ParameterizedTest @ValueSource(strings={"0","-1","abc"})
    void invalidIdsDoNotReachApplication(String id) throws Exception {
        failure(send("/api/reservation-processes/"+id+"/cancel","{}",42),400,"INVALID_REQUEST");
        verifyNoInteractions(store,remote,listing);
    }
    @Test void foreignOwnershipHasTheSame404AsMissing() throws Exception {
        when(store.prepareCancellation(eq(9L),eq(new AuthenticatedReservationUser(43,"alice")),nullable(String.class),any()))
                .thenThrow(new ReservationProcessStateException(ReservationProcessStateException.Reason.NOT_FOUND));
        failure(send(PATH,"{}",43),404,"RESERVATION_PROCESS_NOT_FOUND"); verifyNoInteractions(remote);
    }
    @Test void missingBearerIs401BeforeApplication() throws Exception {
        failure(mvc.perform(post(PATH).contentType("application/json").content("{}")),401,"UNAUTHORIZED");
        verifyNoInteractions(store,remote,listing);
    }
    @Test void technical401DoesNotBecomeFinalUser401() throws Exception {
        when(remote.cancel(anyString(),isNull())).thenThrow(new ReservationCancellationException(ReservationCancellationException.Reason.UNAUTHORIZED));
        failure(send(PATH,"{}",42),502,"CATEDRA_UNAUTHORIZED");
    }
    @ParameterizedTest
    @CsvSource({"RESERVATION_OWNER_MISMATCH,502,CATEDRA_OWNER_MISMATCH", "RESERVATION_NOT_FOUND,502,CATEDRA_RESERVATION_NOT_FOUND",
            "RESERVATION_NOT_CANCELLABLE,409,RESERVATION_NOT_CANCELLABLE", "FORBIDDEN,502,CATEDRA_FORBIDDEN"})
    void remoteRejectionsRemainDistinct(String reason,int expectedStatus,String code) throws Exception {
        when(remote.cancel(anyString(),isNull())).thenThrow(new ReservationCancellationException(
                ReservationCancellationException.Reason.valueOf(reason)));
        failure(send(PATH,"{}",42),expectedStatus,code);
    }
    @Test void uncertainResultIs503AndDoesNotExposeDiagnostics() throws Exception {
        when(remote.cancel(anyString(),isNull())).thenThrow(new ReservationCancellationException(ReservationCancellationException.Reason.TIMEOUT));
        when(store.findOwned(9,OWNER)).thenThrow(new IllegalStateException("private diagnostic"));
        failure(send(PATH,"{}",42),503,"CANCELLATION_OUTCOME_UNKNOWN");
    }
    @Test void requestNeverSerializesReason() {
        var request=new CancelReservationRequest("private reason");
        assertThat(request.toString()).doesNotContain("private reason");
        assertThat(json.writeValueAsString(request)).doesNotContain("reason");
    }
    private ResultActions send(String path,String body,long owner) throws Exception {
        var properties=new FinalUserJwtProperties(); properties.setBase64Secret(SECRET);
        var token=new NimbusFinalUserTokenIssuer(properties,Clock.systemUTC()).issue(new FinalUserPrincipal(owner,"alice","test",true,
                List.of(new SimpleGrantedAuthority("ROLE_USER"))),false).value();
        return mvc.perform(post(path).header("Authorization","Bearer "+token).contentType("application/json").content(body));
    }
    private void failure(ResultActions result,int statusCode,String code) throws Exception {
        result.andExpect(status().is(statusCode)).andExpect(jsonPath("$.code").value(code))
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(header().string("Cache-Control","no-store"));
    }
    @TestConfiguration(proxyBeanMethods=false) static class Settings {
        @Bean JwtDecoder decoder() {
            var properties=new FinalUserJwtProperties();properties.setBase64Secret(SECRET);
            return new FinalUserSecurityConfiguration().finalUserJwtDecoder(properties);
        }
        @Bean CancelOwnedReservationUseCase cancel(ReservationProcessStore store,ReservationCanceller remote,ListOwnedReservationsUseCase listing) {
            return new CancelOwnedReservationUseCase(new SpringCurrentReservationUser(),store,remote,listing,Clock.fixed(NOW,ZoneOffset.UTC));
        }
    }
}
