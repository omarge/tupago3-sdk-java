package com.tupago.sdk.error;

import io.grpc.Status;

/**
 * Base exception for all TUPAGO3 SDK failures. Wraps the originating gRPC {@link Status}
 * so the backend's frozen error matrix (see SDK-JAVA-00 contract) is preserved verbatim.
 */
public class TupagoException extends RuntimeException {

    private final Status status;
    private final boolean retryable;
    private final boolean pendingReconciliation;

    public TupagoException(Status status, String message, boolean retryable, boolean pendingReconciliation) {
        super(message);
        this.status = status;
        this.retryable = retryable;
        this.pendingReconciliation = pendingReconciliation;
    }

    public TupagoException(Status status, String message, boolean retryable, boolean pendingReconciliation, Throwable cause) {
        super(message, cause);
        this.status = status;
        this.retryable = retryable;
        this.pendingReconciliation = pendingReconciliation;
    }

    /** The gRPC status carried from the backend (code + description preserved). */
    public Status status() {
        return status;
    }

    public Status.Code code() {
        return status.getCode();
    }

    public String description() {
        return status.getDescription();
    }

    /** Whether the SDK is permitted to retry the operation that produced this error. */
    public boolean isRetryable() {
        return retryable;
    }

    /**
     * True only for the non-transient {@code TUPAGO_PENDING_RECONCILIATION} (ABORTED) condition.
     * When set, the caller MUST query status instead of resubmitting (gate G6).
     */
    public boolean isPendingReconciliation() {
        return pendingReconciliation;
    }
}
