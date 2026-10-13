package com.tupago.sdk.core.error;

/**
 * Transport-neutral payment error taxonomy (SDK-01, section 12). Reflects the SDK-00 contract.
 *
 * <p>These types are intentionally decoupled from HTTP status codes, GraphQL error objects and
 * {@code io.grpc.StatusRuntimeException}; the transport adapters translate into them and never leak
 * transport-specific types past the core boundary.</p>
 *
 * <p>Retry posture: every category is NON-retryable by default except {@link TransportUnavailable},
 * whose retryability additionally depends on whether the request may have been sent (see
 * {@link com.tupago.sdk.core.retry.RetryClassifier}). {@link PendingReconciliation} is unconditionally
 * non-retryable — it is the critical financial "unknown remote state" condition.</p>
 */
public sealed interface PaymentError
        permits PaymentError.AuthenticationFailure, PaymentError.AuthorizationFailure, PaymentError.ValidationFailure,
                PaymentError.IdempotencyConflict, PaymentError.PolicyRevisionConflict, PaymentError.PaymentLimitExceeded,
                PaymentError.PaymentLimitPolicyDisabled, PaymentError.PaymentLimitPolicyMissing, PaymentError.NotFound,
                PaymentError.PendingReconciliation, PaymentError.TransportUnavailable, PaymentError.InternalFailure {

    /** Stable, machine-detectable code (no spaces; uppercase). */
    String code();

    String message();

    ErrorCategory category();

    /** Default retry posture absent transport evidence. Overridden to {@code false} for PENDING_RECONCILIATION. */
    default boolean isRetryableByDefault() {
        return category() == ErrorCategory.TRANSPORT_UNAVAILABLE;
    }

    // ---- nested permitted subtypes (each a distinct, named, public type) ----

    record AuthenticationFailure(String message) implements PaymentError {
        @Override public String code() { return "AUTHENTICATION_FAILURE"; }
        @Override public ErrorCategory category() { return ErrorCategory.AUTHENTICATION; }
    }

    record AuthorizationFailure(String message) implements PaymentError {
        @Override public String code() { return "AUTHORIZATION_FAILURE"; }
        @Override public ErrorCategory category() { return ErrorCategory.AUTHORIZATION; }
    }

    record ValidationFailure(String message) implements PaymentError {
        @Override public String code() { return "VALIDATION_FAILURE"; }
        @Override public ErrorCategory category() { return ErrorCategory.VALIDATION; }
    }

    record IdempotencyConflict(String message) implements PaymentError {
        @Override public String code() { return "IDEMPOTENCY_CONFLICT"; }
        @Override public ErrorCategory category() { return ErrorCategory.IDEMPOTENCY_CONFLICT; }
    }

    record PolicyRevisionConflict(String message) implements PaymentError {
        @Override public String code() { return "POLICY_REVISION_CONFLICT"; }
        @Override public ErrorCategory category() { return ErrorCategory.POLICY_REVISION_CONFLICT; }
    }

    record PaymentLimitExceeded(String message) implements PaymentError {
        @Override public String code() { return "PAYMENT_LIMIT"; }
        @Override public ErrorCategory category() { return ErrorCategory.PAYMENT_LIMIT; }
    }

    record PaymentLimitPolicyDisabled(String message) implements PaymentError {
        @Override public String code() { return "PAYMENT_LIMIT_POLICY_DISABLED"; }
        @Override public ErrorCategory category() { return ErrorCategory.PAYMENT_LIMIT_POLICY_DISABLED; }
    }

    record PaymentLimitPolicyMissing(String message) implements PaymentError {
        @Override public String code() { return "PAYMENT_LIMIT_POLICY_MISSING"; }
        @Override public ErrorCategory category() { return ErrorCategory.PAYMENT_LIMIT_POLICY_MISSING; }
    }

    record NotFound(String message) implements PaymentError {
        @Override public String code() { return "NOT_FOUND"; }
        @Override public ErrorCategory category() { return ErrorCategory.NOT_FOUND; }
    }

    /**
     * CRITICAL FINANCIAL CONTRACT — unknown remote state. NEVER retry; the caller must query status.
     * Backend representations: REST 202 / payment-pending-reconciliation, GraphQL
     * PAYMENT_PENDING_RECONCILIATION, gRPC ABORTED + TUPAGO_PENDING_RECONCILIATION.
     */
    record PendingReconciliation(String message) implements PaymentError {
        @Override public String code() { return "PAYMENT_PENDING_RECONCILIATION"; }
        @Override public ErrorCategory category() { return ErrorCategory.PENDING_RECONCILIATION; }
        @Override public boolean isRetryableByDefault() { return false; }
    }

    /**
     * Transport-level failure where we cannot tell whether the request reached the backend.
     * {@code mayHaveBeenSent == true} means a create MUST be reconciled, never blindly retried.
     */
    record TransportUnavailable(boolean mayHaveBeenSent, String message) implements PaymentError {
        @Override public String code() { return "TRANSPORT_UNAVAILABLE"; }
        @Override public ErrorCategory category() { return ErrorCategory.TRANSPORT_UNAVAILABLE; }
    }

    record InternalFailure(String message) implements PaymentError {
        @Override public String code() { return "INTERNAL_FAILURE"; }
        @Override public ErrorCategory category() { return ErrorCategory.INTERNAL; }
    }
}
