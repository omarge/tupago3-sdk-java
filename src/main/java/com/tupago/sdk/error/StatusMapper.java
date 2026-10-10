package com.tupago.sdk.error;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;

/**
 * Maps a gRPC {@link StatusRuntimeException} to the SDK exception hierarchy, preserving the
 * backend's frozen error matrix (SDK-JAVA-00 section 2/8). The original status code and
 * description are carried verbatim so {@code TUPAGO_PENDING_RECONCILIATION} stays
 * machine-detectable.
 */
public final class StatusMapper {

    /** Stable code embedded by the backend in the ABORTED status description (gate G6). */
    public static final String PENDING_RECONCILIATION_CODE = "TUPAGO_PENDING_RECONCILIATION";

    private StatusMapper() {}

    public static TupagoException map(StatusRuntimeException ex) {
        Status status = ex.getStatus();
        return map(status, ex);
    }

    public static TupagoException map(Status status, Throwable cause) {
        String description = status.getDescription() == null ? "" : status.getDescription();
        boolean pending = description.contains(PENDING_RECONCILIATION_CODE);
        switch (status.getCode()) {
            case UNAUTHENTICATED:
                return new TupagoUnauthenticatedException(status);
            case PERMISSION_DENIED:
                return new TupagoPermissionDeniedException(status);
            case INVALID_ARGUMENT:
                return new TupagoInvalidArgumentException(status);
            case ALREADY_EXISTS:
                return new TupagoAlreadyExistsException(status);
            case FAILED_PRECONDITION:
                return new TupagoFailedPreconditionException(status);
            case NOT_FOUND:
                return new TupagoNotFoundException(status);
            case ABORTED:
                return new TupagoAbortedException(status);
            case UNAVAILABLE:
            case DEADLINE_EXCEEDED:
                return new TupagoTransientException(status);
            case INTERNAL:
                return new TupagoInternalException(status);
            default:
                return new TupagoException(status, status.getCode() + ": " + description, false, pending, cause);
        }
    }
}
