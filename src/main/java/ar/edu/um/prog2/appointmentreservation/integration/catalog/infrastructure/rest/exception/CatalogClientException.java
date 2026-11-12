package ar.edu.um.prog2.appointmentreservation.integration.catalog.infrastructure.rest.exception;

import java.util.OptionalInt;

import org.jspecify.annotations.Nullable;

/** Safe technical failure data; remote bodies, headers and exception causes are not retained. */
public final class CatalogClientException extends RuntimeException {

	public enum Reason {
		HTTP,
		INVALID_RESPONSE,
		TIMEOUT,
		TRANSPORT
	}

	private final Reason reason;
	private final @Nullable Integer status;

	private CatalogClientException(Reason reason, @Nullable Integer status, String message) {
		super(message);
		this.reason = reason;
		this.status = status;
	}

	public static CatalogClientException http(int status) {
		return new CatalogClientException(Reason.HTTP, status, "Catalog request failed with HTTP status " + status);
	}

	public static CatalogClientException invalidResponse() {
		return new CatalogClientException(Reason.INVALID_RESPONSE, null, "Catalog response does not match the internal contract");
	}

	public static CatalogClientException transport() {
		return new CatalogClientException(Reason.TRANSPORT, null, "Catalog request could not be completed");
	}

	public static CatalogClientException timeout() {
		return new CatalogClientException(Reason.TIMEOUT, null, "Catalog request timed out");
	}

	public Reason reason() {
		return reason;
	}

	public OptionalInt status() {
		return status == null ? OptionalInt.empty() : OptionalInt.of(status);
	}
}
