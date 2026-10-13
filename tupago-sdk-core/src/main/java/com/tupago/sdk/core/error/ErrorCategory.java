package com.tupago.sdk.core.error;

/**
 * Stable machine categories for {@link PaymentError}, mirroring the SDK-00 cross-protocol taxonomy.
 * A transport adapter maps REST/GraphQL/gRPC representations onto these categories so the SDK behaves
 * identically on every transport.
 */
public enum ErrorCategory {
    AUTHENTICATION,
    AUTHORIZATION,
    VALIDATION,
    IDEMPOTENCY_CONFLICT,
    POLICY_REVISION_CONFLICT,
    PAYMENT_LIMIT,
    PAYMENT_LIMIT_POLICY_DISABLED,
    PAYMENT_LIMIT_POLICY_MISSING,
    NOT_FOUND,
    PENDING_RECONCILIATION,
    TRANSPORT_UNAVAILABLE,
    INTERNAL
}
