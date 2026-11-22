package ar.edu.um.prog2.appointmentreservation.reservation.application.usecase;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import ar.edu.um.prog2.appointmentreservation.reservation.application.exception.OwnedReservationQueryException;
import ar.edu.um.prog2.appointmentreservation.reservation.application.exception.OwnedReservationQueryException.Reason;
import ar.edu.um.prog2.appointmentreservation.reservation.application.exception.ReservationAuthenticationRequiredException;
import ar.edu.um.prog2.appointmentreservation.reservation.application.exception.ReservationListingException;
import ar.edu.um.prog2.appointmentreservation.reservation.application.model.*;
import ar.edu.um.prog2.appointmentreservation.reservation.application.port.out.CurrentReservationUser;
import ar.edu.um.prog2.appointmentreservation.reservation.application.port.out.ReservationProcessStore;
import ar.edu.um.prog2.appointmentreservation.reservation.application.port.out.TechnicalReservationSource;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;

class ListOwnedReservationsUseCaseTest {
	private static final AuthenticatedReservationUser OWNER = new AuthenticatedReservationUser(42, "alice");
	private static final LocalDate DATE = LocalDate.of(2026, 12, 1);
	private final CurrentReservationUser current = mock(CurrentReservationUser.class);
	private final TechnicalReservationSource remote = mock(TechnicalReservationSource.class);
	private final ReservationProcessStore processes = mock(ReservationProcessStore.class);
	private final ListOwnedReservationsUseCase useCase = new ListOwnedReservationsUseCase(current, remote, processes);

	private void authenticated() { when(current.requireAuthenticated()).thenReturn(OWNER); }
	private static OwnedReservationQuery all() { return new OwnedReservationQuery(null, null, null, 0, 20); }

	@Test void refusesUnauthenticatedQueriesBeforeUsingDependencies() {
		when(current.requireAuthenticated()).thenThrow(new ReservationAuthenticationRequiredException());
		assertThatThrownBy(() -> useCase.list(all())).isInstanceOf(ReservationAuthenticationRequiredException.class);
		verifyNoInteractions(remote, processes);
	}

	@Test void derivesPatientFromTrustedUserAndDoesNotAuthorizeByNames() {
		authenticated();
		var row = row(1, "user-42");
		when(remote.list(any())).thenReturn(new TechnicalReservationPage(List.of(row), 1));
		when(processes.findReservationAssociations(Set.of("process-1"), Set.of(1L))).thenReturn(List.of(association(1, 42, 1L)));
		var result = useCase.list(all());
		assertThat(result.total()).isEqualTo(1);
		assertThat(result.items()).containsExactly(new OwnedReservationPage.Item(1001, row));
		verify(remote).list(new ReservationListQuery(null, null, null, "user-42", 0, 100, List.of()));
		assertThat(result.toString() + result.items().getFirst()).doesNotContain("Private name", "+5492615550000");
	}

	@Test void twoUsersOfTheSameTechnicalAccountReceiveOnlyTheirLocallyAssociatedReservations() {
		when(current.requireAuthenticated()).thenReturn(OWNER, new AuthenticatedReservationUser(43, "bob"));
		when(remote.list(any())).thenAnswer(call -> {
			ReservationListQuery query = call.getArgument(0);
			long id = query.externalPatientId().equals("user-42") ? 1 : 2;
			return new TechnicalReservationPage(List.of(row(id, query.externalPatientId())), 1);
		});
		when(processes.findReservationAssociations(any(), any())).thenAnswer(call -> {
			Set<String> ids = call.getArgument(0);
			return ids.contains("process-1") ? List.of(association(1, 42, 1L)) : List.of(association(2, 43, 2L));
		});
		assertThat(useCase.list(all()).items()).extracting(item -> item.reservation().reservationId()).containsExactly(1L);
		assertThat(useCase.list(all()).items()).extracting(item -> item.reservation().reservationId()).containsExactly(2L);
	}

	@Test void excludesUnassociatedRowsAndComputesAuthorizedTotalAndPagesAfterAllRemotePages() {
		authenticated();
		var first = IntStream.rangeClosed(1, 100).mapToObj(id -> row(id, "user-42")).toList();
		var second = List.of(row(101, "user-42"), row(102, "user-42"), row(103, "user-42"));
		when(remote.list(any())).thenReturn(new TechnicalReservationPage(first, 103), new TechnicalReservationPage(second, 103));
		when(processes.findReservationAssociations(any(), any())).thenReturn(List.of(association(2, 42, 2L), association(102, 42, null)));
		var result = useCase.list(new OwnedReservationQuery(null, null, null, 1, 1));
		assertThat(result.total()).isEqualTo(2);
		assertThat(result.items()).extracting(item -> item.reservation().reservationId()).containsExactly(102L);
		assertThat(result.page()).isEqualTo(1); assertThat(result.size()).isEqualTo(1);
		verify(remote).list(new ReservationListQuery(null, null, null, "user-42", 0, 100, List.of()));
		verify(remote).list(new ReservationListQuery(null, null, null, "user-42", 1, 100, List.of()));
		verify(processes).findReservationAssociations(any(), any());
	}

	@Test void emptyRemoteResultDoesNotMeanLocalReservationsHaveBeenDeleted() {
		authenticated();
		when(remote.list(any())).thenReturn(new TechnicalReservationPage(List.of(), 0));
		assertThat(useCase.list(all()).items()).isEmpty();
		verifyNoInteractions(processes);
	}

	@Test void validOutOfRangePublicPagePreservesTheAuthorizedTotalWithoutIntegerOverflow() {
		authenticated();
		when(remote.list(any())).thenReturn(new TechnicalReservationPage(List.of(row(1, "user-42")), 1));
		when(processes.findReservationAssociations(any(), any())).thenReturn(List.of(association(1, 42, 1L)));
		var result = useCase.list(new OwnedReservationQuery(null, null, null, Integer.MAX_VALUE, 100));
		assertThat(result.items()).isEmpty(); assertThat(result.total()).isEqualTo(1);
	}

	@ParameterizedTest @ValueSource(strings = {"otherPatient", "status", "before", "after"})
	void rejectsRowsOutsideTrustedFiltersEvenIfTheTechnicalPortReturnsThem(String scenario) {
		authenticated();
		var query = new OwnedReservationQuery(TechnicalReservation.Status.CONFIRMED, DATE, DATE, 0, 10);
		var row = row(1, scenario.equals("otherPatient") ? "user-43" : "user-42");
		if (scenario.equals("status")) { row = with(row, DATE, TechnicalReservation.Status.PHONE_PENDING); }
		if (scenario.equals("before")) { row = with(row, DATE.minusDays(1), TechnicalReservation.Status.CONFIRMED); }
		if (scenario.equals("after")) { row = with(row, DATE.plusDays(1), TechnicalReservation.Status.CONFIRMED); }
		when(remote.list(any())).thenReturn(new TechnicalReservationPage(List.of(row), 1));
		assertFailure(query, Reason.INVALID_RESPONSE); verifyNoInteractions(processes);
	}

	@Test void forwardsInclusiveDateAndStatusFiltersWithoutExposingCallerControlledOwnershipOrSorting() {
		authenticated();
		when(remote.list(any())).thenReturn(new TechnicalReservationPage(List.of(row(1, "user-42")), 1));
		when(processes.findReservationAssociations(any(), any())).thenReturn(List.of(association(1, 42, 1L)));
		assertThat(useCase.list(new OwnedReservationQuery(TechnicalReservation.Status.CONFIRMED, DATE, DATE, 0, 10)).total()).isEqualTo(1);
		verify(remote).list(new ReservationListQuery(TechnicalReservation.Status.CONFIRMED, DATE, DATE, "user-42", 0, 100, List.of()));
	}

	@ParameterizedTest @ValueSource(strings = {"owner", "patient", "reservationId", "professional", "date", "startTime", "crossProcess"})
	void refusesContradictoryLocalEvidenceWithoutReturningPartialData(String scenario) {
		authenticated();
		when(remote.list(any())).thenReturn(new TechnicalReservationPage(List.of(row(1, "user-42")), 1));
		var association = association(1, 42, 1L);
		association = switch (scenario) {
			case "owner" -> association(1, 43, 1L);
			case "patient" -> new ReservationAssociation(1001, 42, "user-43", "process-1", 1L, 202, DATE, LocalTime.NOON);
			case "reservationId" -> association(1, 42, 2L);
			case "professional" -> new ReservationAssociation(1001, 42, "user-42", "process-1", 1L, 203, DATE, LocalTime.NOON);
			case "date" -> new ReservationAssociation(1001, 42, "user-42", "process-1", 1L, 202, DATE.plusDays(1), LocalTime.NOON);
			case "startTime" -> new ReservationAssociation(1001, 42, "user-42", "process-1", 1L, 202, DATE, LocalTime.MIDNIGHT);
			case "crossProcess" -> association(2, 43, 1L);
			default -> association;
		};
		when(processes.findReservationAssociations(any(), any())).thenReturn(List.of(association));
		assertFailure(all(), Reason.CONFLICTING_ASSOCIATION);
	}

	@Test void refusesProcessAndReservationIdsThatMatchDifferentLocalProcesses() {
		authenticated();
		when(remote.list(any())).thenReturn(new TechnicalReservationPage(List.of(row(1, "user-42")), 1));
		when(processes.findReservationAssociations(any(), any())).thenReturn(List.of(association(1, 42, null), association(2, 42, 1L)));
		assertFailure(all(), Reason.CONFLICTING_ASSOCIATION);
	}

	@ParameterizedTest @ValueSource(strings = {"process", "reservation"})
	void rejectsDuplicateExternalIdentitiesAcrossRows(String duplicate) {
		authenticated();
		var row = row(1, "user-42"); var other = row(2, "user-42");
		other = new TechnicalReservation(duplicate.equals("reservation") ? 1 : 2,
				duplicate.equals("process") ? "process-1" : "process-2", other.externalPatientId(), DATE,
				other.startTime(), other.endTime(), 202, null, null, null, null, null, other.status(), other.createdAt(), null, null, null);
		when(remote.list(any())).thenReturn(new TechnicalReservationPage(List.of(row, other), 2));
		assertFailure(all(), Reason.CONFLICTING_ASSOCIATION); verifyNoInteractions(processes);
	}

	@ParameterizedTest @ValueSource(longs = {101, 1001, Long.MAX_VALUE})
	void doesNotReturnAnIncompleteResultOrUseAnUnboundedScan(long total) {
		authenticated();
		when(remote.list(any())).thenReturn(new TechnicalReservationPage(List.of(), total));
		assertFailure(all(), Reason.INCOMPLETE_RESULT); verifyNoInteractions(processes);
		verify(remote, times(1)).list(any());
	}

	@Test void refusesChangingTotalsAcrossRemotePages() {
		authenticated();
		var first = IntStream.rangeClosed(1,100).mapToObj(id -> row(id,"user-42")).toList();
		when(remote.list(any())).thenReturn(new TechnicalReservationPage(first,101), new TechnicalReservationPage(List.of(row(101,"user-42")),102));
		assertFailure(all(), Reason.INCOMPLETE_RESULT); verifyNoInteractions(processes);
	}

	@Test void readsAtMostTenRemotePages() {
		authenticated();
		when(remote.list(any())).thenAnswer(call -> {
			ReservationListQuery query = call.getArgument(0);
			var rows = IntStream.rangeClosed(query.page()*100+1, query.page()*100+100).mapToObj(id -> row(id,"user-42")).toList();
			return new TechnicalReservationPage(rows,1000);
		});
		when(processes.findReservationAssociations(any(), any())).thenReturn(List.of());
		assertThat(useCase.list(all()).total()).isZero();
		verify(remote,times(10)).list(any()); verify(processes).findReservationAssociations(any(),any());
	}

	@Test void externalFailureIsNotReportedAsASuccessfulEmptyList() {
		authenticated();
		var failure = new ReservationListingException(ReservationListingException.Reason.UNAVAILABLE);
		when(remote.list(any())).thenThrow(failure);
		assertThatThrownBy(() -> useCase.list(all())).isSameAs(failure);
		verifyNoInteractions(processes);
	}

	@Test void rejectsInvalidPublicPaginationAndDateBounds() {
		assertThatThrownBy(() -> new OwnedReservationQuery(null,null,null,-1,10)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new OwnedReservationQuery(null,null,null,0,0)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new OwnedReservationQuery(null,null,null,0,101)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new OwnedReservationQuery(null,DATE.plusDays(1),DATE,0,10)).isInstanceOf(IllegalArgumentException.class);
		assertThat(new OwnedReservationQuery(null,DATE,null,0,100).from()).isEqualTo(DATE);
	}

	private void assertFailure(OwnedReservationQuery query,Reason reason) {
		assertThatThrownBy(() -> useCase.list(query)).isInstanceOfSatisfying(OwnedReservationQueryException.class,
				failure -> assertThat(failure.reason()).isEqualTo(reason)).hasNoCause().hasMessageNotContaining("Private name");
	}
	private static ReservationAssociation association(long id,long owner,Long reservationId) {
		return new ReservationAssociation(1000+id,owner,"user-"+owner,"process-"+id,reservationId,202,DATE,LocalTime.NOON);
	}
	private static TechnicalReservation row(long id,String patient) {
		return new TechnicalReservation(id,"process-"+id,patient,DATE,LocalTime.NOON,LocalTime.of(12,30),202,
				"Private name","Private surname","Same patient","Same surname","+5492615550000",TechnicalReservation.Status.CONFIRMED,
				Instant.parse("2026-11-22T12:00:00Z"),Instant.parse("2026-11-22T12:01:00Z"),null,null);
	}
	private static TechnicalReservation with(TechnicalReservation row,LocalDate date,TechnicalReservation.Status status) {
		return new TechnicalReservation(row.reservationId(),row.reservationProcessId(),row.externalPatientId(),date,
				row.startTime(),row.endTime(),202,null,null,null,null,null,status,row.createdAt(),null,null,null);
	}
}
