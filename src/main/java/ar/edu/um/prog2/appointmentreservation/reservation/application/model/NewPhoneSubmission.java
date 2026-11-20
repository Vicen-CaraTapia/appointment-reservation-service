package ar.edu.um.prog2.appointmentreservation.reservation.application.model;

/** Backend-only command: normalization, UUID generation and configuration lookup belong to A8.3. */
public record NewPhoneSubmission(long localProcessId, String eventId, String normalizedPhone, String technicalGroupId) {
	public NewPhoneSubmission {
		if (localProcessId <= 0 || eventId == null || eventId.isBlank() || technicalGroupId == null
				|| technicalGroupId.isBlank() || normalizedPhone == null || !normalizedPhone.matches("\\+?[0-9]{7,15}")) {
			throw new IllegalArgumentException("Valid backend submission metadata and a normalized phone are required");
		}
	}
	@Override
	public String toString() { return "NewPhoneSubmission[redacted]"; }
}
