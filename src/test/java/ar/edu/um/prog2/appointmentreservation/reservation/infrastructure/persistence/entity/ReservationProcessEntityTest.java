package ar.edu.um.prog2.appointmentreservation.reservation.infrastructure.persistence.entity;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import ar.edu.um.prog2.appointmentreservation.reservation.application.ExternalPatientId;
import ar.edu.um.prog2.appointmentreservation.reservation.domain.model.ReservationProcessStatus;
import ar.edu.um.prog2.appointmentreservation.user.infrastructure.persistence.entity.UserEntity;
import ar.edu.um.prog2.appointmentreservation.user.infrastructure.security.FinalUserSecurityConfiguration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReservationProcessEntityTest {

	private static final LocalDate DATE = LocalDate.of(2026, 11, 17);
	private static final LocalTime TIME = LocalTime.of(11, 30);
	private static final String HASH = new FinalUserSecurityConfiguration().passwordEncoder()
			.encode("test-only-process-passphrase");

	@Test
	void createsAnOwnedPendingProcessWithoutInventingExternalState() {
		var owner = owner();
		var process = new ReservationProcessEntity(owner, Long.MAX_VALUE, DATE, TIME,
				ExternalPatientId.fromUserId(owner.getId()), "backend-test");
		assertThat(process.getId()).isNull();
		assertThat(process.getOwner()).isSameAs(owner);
		assertThat(process.getProfessionalId()).isEqualTo(Long.MAX_VALUE);
		assertThat(process.getAppointmentDate()).isEqualTo(DATE);
		assertThat(process.getStartTime()).isEqualTo(TIME);
		assertThat(process.getExternalPatientId()).isEqualTo("user-42");
		assertThat(process.getStatus()).isEqualTo(ReservationProcessStatus.PENDING_HOLD);
		assertThat(process.getHoldId()).isNull();
		assertThat(process.getExternalReservationProcessId()).isNull();
		assertThat(process.getExpiresAt()).isNull();
		assertThat(process.getCreatedBy()).isEqualTo("backend-test");
		assertThat(process.getCreatedDate()).isNull();
		assertThat(process.getLastModifiedBy()).isNull();
		assertThat(process.getLastModifiedDate()).isNull();
	}

	@Test
	void storesACompleteHoldWithoutReinterpretingOrExtendingItsExpiration() {
		var process = pending();
		process.markHeld("hold/opaque:123", "process/opaque:456", Instant.EPOCH, "backend-test");
		assertThat(process.getStatus()).isEqualTo(ReservationProcessStatus.HELD);
		assertThat(process.getHoldId()).isEqualTo("hold/opaque:123");
		assertThat(process.getExternalReservationProcessId()).isEqualTo("process/opaque:456");
		assertThat(process.getExpiresAt()).isEqualTo(Instant.EPOCH);
	}

	@Test
	void doesNotAllowReplacingAnAlreadyAttachedHold() {
		var process = pending();
		process.markHeld("hold-first", "process-first", Instant.EPOCH, "backend-test");
		assertThatThrownBy(() -> process.markHeld("hold-other", "process-other", Instant.EPOCH, "backend-test"))
				.isInstanceOf(IllegalStateException.class);
		assertThat(process.getHoldId()).isEqualTo("hold-first");
	}

	@ParameterizedTest
	@MethodSource("invalidHoldDetails")
	void rejectsIncompleteHoldDetailsWithoutChangingPendingState(String hold, String externalProcess, Instant expires,
			String actor, Class<? extends RuntimeException> expected) {
		var process = pending();
		assertThatThrownBy(() -> process.markHeld(hold, externalProcess, expires, actor)).isInstanceOf(expected);
		assertThat(process.getStatus()).isEqualTo(ReservationProcessStatus.PENDING_HOLD);
		assertThat(process.getHoldId()).isNull();
		assertThat(process.getExternalReservationProcessId()).isNull();
		assertThat(process.getExpiresAt()).isNull();
		assertThat(process.getLastModifiedBy()).isNull();
	}

	static Stream<Arguments> invalidHoldDetails() {
		return Stream.of(
				Arguments.of(null, "process", Instant.EPOCH, "actor", IllegalArgumentException.class),
				Arguments.of(" ", "process", Instant.EPOCH, "actor", IllegalArgumentException.class),
				Arguments.of("hold", null, Instant.EPOCH, "actor", IllegalArgumentException.class),
				Arguments.of("hold", "\t", Instant.EPOCH, "actor", IllegalArgumentException.class),
				Arguments.of("hold", "process", null, "actor", NullPointerException.class),
				Arguments.of("hold", "process", Instant.EPOCH, null, IllegalArgumentException.class),
				Arguments.of("hold", "process", Instant.EPOCH, " ", IllegalArgumentException.class),
				Arguments.of("hold", "process", Instant.EPOCH, "a".repeat(51), IllegalArgumentException.class));
	}

	@Test
	void requiresAnExistingOwnerAndApplicationDerivedPatientIdentity() {
		var unsaved = new UserEntity("alice", HASH, null, null, "alice@example.test", null, "es", "actor");
		assertThatThrownBy(() -> new ReservationProcessEntity(null, 201, DATE, TIME, "user-42", "actor"))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new ReservationProcessEntity(unsaved, 201, DATE, TIME, "user-42", "actor"))
				.isInstanceOf(IllegalArgumentException.class);
		var owner = owner();
		assertThatThrownBy(() -> new ReservationProcessEntity(owner, 201, DATE, TIME, "user-999", "actor"))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new ReservationProcessEntity(owner, 201, DATE, TIME, null, "actor"))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@ParameterizedTest
	@ValueSource(strings = {"", " ", "\t"})
	void requiresANonblankCreationAuditActor(String actor) {
		var owner = owner();
		assertThatThrownBy(() -> new ReservationProcessEntity(owner, 201, DATE, TIME, "user-42", actor))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void rejectsMissingSlotDataAndInvalidAuditLengths() {
		var owner = owner();
		String tooLong = "a".repeat(51);
		assertThatThrownBy(() -> new ReservationProcessEntity(owner, 201, null, TIME, "user-42", "actor"))
				.isInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> new ReservationProcessEntity(owner, 201, DATE, null, "user-42", "actor"))
				.isInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> new ReservationProcessEntity(owner, 201, DATE, TIME, "user-42", tooLong))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new ReservationProcessEntity(owner, 201, DATE, TIME, "user-42", null))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void recordsInitialAcceptanceWithPatientDetailsAndAuthoritativeMicrosecondExpiry() {
		var process = claimed(" Ana ", "Pérez");
		var expiry = Instant.parse("2026-11-19T12:34:56.123456789Z");
		process.markWaitingForPhone(" Ana ", "Pérez", expiry, "confirmation-adapter");
		assertThat(process.getStatus()).isEqualTo(ReservationProcessStatus.WAITING_FOR_PHONE);
		assertThat(process.getPatientFirstName()).isEqualTo(" Ana ");
		assertThat(process.getPatientLastName()).isEqualTo("Pérez");
		assertThat(process.getExpiresAt()).isEqualTo(Instant.parse("2026-11-19T12:34:56.123456Z"))
				.isBeforeOrEqualTo(expiry);
		assertThat(process.getHoldId()).isEqualTo("hold-first");
		assertThat(process.getExternalReservationProcessId()).isEqualTo("process-first");
		assertThat(process.getExternalPatientId()).isEqualTo("user-42");
		assertThat(process.getOwner().getId()).isEqualTo(42L);
		assertThat(process.getLastModifiedBy()).isEqualTo("confirmation-adapter");
		assertThat(process.getLastModifiedDate()).isNotNull();
		assertThat(process.getVersion()).isNull();
	}

	@ParameterizedTest
	@ValueSource(ints = {2, 100})
	void acceptsBothPatientNameLengthBoundaries(int length) {
		var process = claimed("a".repeat(length), "b".repeat(length));
		process.markWaitingForPhone("a".repeat(length), "b".repeat(length), Instant.EPOCH, "actor");
		assertThat(process.getPatientFirstName()).hasSize(length);
		assertThat(process.getPatientLastName()).hasSize(length);
	}

	@ParameterizedTest
	@MethodSource("invalidConfirmationDetails")
	void rejectsInvalidConfirmationWithoutPartiallyMutatingTheClaimedProcess(
			String first, String last, Instant expiry, String actor) {
		var process = claimed("Ana", "Perez");
		var auditDate = process.getLastModifiedDate();
		assertThatThrownBy(() -> process.markWaitingForPhone(first, last, expiry, actor))
				.isInstanceOf(RuntimeException.class)
				.hasMessageNotContaining("private-patient-input");
		assertThat(process.getStatus()).isEqualTo(ReservationProcessStatus.CONFIRMATION_PENDING);
		assertThat(process.getPatientFirstName()).isEqualTo("Ana");
		assertThat(process.getPatientLastName()).isEqualTo("Perez");
		assertThat(process.getExpiresAt()).isEqualTo(Instant.EPOCH);
		assertThat(process.getLastModifiedBy()).isEqualTo("claim-actor");
		assertThat(process.getLastModifiedDate()).isEqualTo(auditDate);
	}

	static Stream<Arguments> invalidConfirmationDetails() {
		var invalidNames = Stream.of(null, "", " ", "\t\n", "a", "private-patient-input".repeat(6));
		return Stream.concat(invalidNames.flatMap(name -> Stream.of(
				Arguments.of(name, "Perez", Instant.EPOCH, "actor"),
				Arguments.of("Ana", name, Instant.EPOCH, "actor"))), Stream.of(
				Arguments.of("Ana", "Perez", null, "actor"),
				Arguments.of("Ana", "Perez", Instant.EPOCH, null),
				Arguments.of("Ana", "Perez", Instant.EPOCH, " "),
				Arguments.of("Ana", "Perez", Instant.EPOCH, "a".repeat(51))));
	}

	@Test
	void rejectsConfirmationWithoutAHoldAndRepeatedOrRegressiveTransitions() {
		assertThatThrownBy(() -> pending().markWaitingForPhone("Ana", "Perez", Instant.EPOCH, "actor"))
				.isInstanceOf(IllegalStateException.class);
		var process = claimed("Ana", "Perez");
		process.markWaitingForPhone("Ana", "Perez", Instant.EPOCH, "actor");
		assertThatThrownBy(() -> process.markWaitingForPhone("Other", "Patient", Instant.MAX, "other"))
				.isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> process.markHeld("other-hold", "other-process", Instant.MAX, "other"))
				.isInstanceOf(IllegalStateException.class);
		assertThat(process.getPatientFirstName()).isEqualTo("Ana");
		assertThat(process.getExpiresAt()).isEqualTo(Instant.EPOCH);
	}

	@Test
	void requiresAClaimAndMatchingPatientBeforeRecordingAcceptance() {
		assertThatThrownBy(() -> held().markWaitingForPhone("Ana", "Perez", Instant.EPOCH, "actor"))
				.isInstanceOf(IllegalStateException.class);
		var process = claimed("Ana", "Perez");
		assertThatThrownBy(() -> process.markWaitingForPhone("Other", "Patient", Instant.EPOCH, "actor"))
				.isInstanceOf(IllegalStateException.class);
		assertThat(process.getStatus()).isEqualTo(ReservationProcessStatus.CONFIRMATION_PENDING);
		assertThat(process.getPatientFirstName()).isEqualTo("Ana");
	}

	@Test
	void claimsOnlyHeldProcessesAndReleasesWithoutChangingHoldOrExtendingExpiry() {
		assertThatThrownBy(() -> pending().claimConfirmation("Ana", "Perez", "actor"))
				.isInstanceOf(IllegalStateException.class);
		var process = claimed("Ana", "Perez");
		assertThatThrownBy(() -> process.claimConfirmation("Other", "Patient", "actor"))
				.isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> process.releaseConfirmation(" ")).isInstanceOf(IllegalArgumentException.class);
		assertThat(process.getPatientFirstName()).isEqualTo("Ana");
		process.releaseConfirmation("release-actor");
		assertThat(process.getStatus()).isEqualTo(ReservationProcessStatus.HELD);
		assertThat(process.getPatientFirstName()).isNull();
		assertThat(process.getPatientLastName()).isNull();
		assertThat(process.getExpiresAt()).isEqualTo(Instant.EPOCH);
		assertThat(process.getHoldId()).isEqualTo("hold-first");
		assertThat(process.getExternalReservationProcessId()).isEqualTo("process-first");
		assertThat(process.getLastModifiedBy()).isEqualTo("release-actor");
		assertThatThrownBy(() -> process.releaseConfirmation("actor")).isInstanceOf(IllegalStateException.class);
	}

	@ParameterizedTest
	@MethodSource("invalidClaimDetails")
	void validatesAllClaimInputsBeforeChangingTheHeldProcess(String first, String last, String actor) {
		var process = held();
		assertThatThrownBy(() -> process.claimConfirmation(first, last, actor)).isInstanceOf(IllegalArgumentException.class);
		assertThat(process.getStatus()).isEqualTo(ReservationProcessStatus.HELD);
		assertThat(process.getPatientFirstName()).isNull();
		assertThat(process.getPatientLastName()).isNull();
		assertThat(process.getLastModifiedBy()).isNull();
	}

	static Stream<Arguments> invalidClaimDetails() {
		return invalidConfirmationDetails().filter(args -> args.get()[2] != null)
				.map(args -> Arguments.of(args.get()[0], args.get()[1], args.get()[3]));
	}

	private static ReservationProcessEntity claimed(String first, String last) {
		var process = held();
		process.claimConfirmation(first, last, "claim-actor");
		return process;
	}

	private static ReservationProcessEntity held() {
		var process = pending();
		process.markHeld("hold-first", "process-first", Instant.EPOCH, "backend-test");
		return process;
	}

	private static ReservationProcessEntity pending() {
		return new ReservationProcessEntity(owner(), 201, DATE, TIME, "user-42", "backend-test");
	}

	private static UserEntity owner() {
		var user = new UserEntity("alice", HASH, null, null, "alice@example.test", null, "es", "actor");
		ReflectionTestUtils.setField(user, "id", 42L);
		return user;
	}
}
