package ar.edu.um.prog2.appointmentreservation.integration.catedra.infrastructure.rest.exception;

import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatusCode;

public final class CatedraHttpException extends CatedraRestException {

	private final HttpStatusCode status;
	private final @Nullable String code;

	public CatedraHttpException(HttpStatusCode status, @Nullable String code) {
		super("Catedra request failed with HTTP status " + status.value());
		this.status = status;
		this.code = code;
	}

	public HttpStatusCode status() {
		return status;
	}

	public @Nullable String code() {
		return code;
	}
}
