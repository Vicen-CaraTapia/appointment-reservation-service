package ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.rest.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Outbound initial confirmation; patient identity must come from the owned local process. */
public record CatedraAppointmentHoldConfirmationRequest(
		@JsonProperty(required = true) String externalPatientId,
		@JsonProperty(required = true) String patientFirstName,
		@JsonProperty(required = true) String patientLastName
) {

	public CatedraAppointmentHoldConfirmationRequest {
		if (externalPatientId == null || externalPatientId.isBlank() || externalPatientId.length() > 100) {
			throw new IllegalArgumentException("External patient ID must be nonblank and at most 100 characters");
		}
		if (!validName(patientFirstName) || !validName(patientLastName)) {
			throw new IllegalArgumentException("Patient names must be nonblank and contain 2 to 100 characters");
		}
	}

	private static boolean validName(String value) {
		return value != null && !value.isBlank() && value.length() >= 2 && value.length() <= 100;
	}

	@Override
	public String toString() {
		return "CatedraAppointmentHoldConfirmationRequest[redacted]";
	}
}
