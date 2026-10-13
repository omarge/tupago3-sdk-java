package com.tupago.sdk.core.result;

/**
 * Transport-neutral outcome of any payment operation (SDK-01, section 11).
 *
 * <p>The sealed union makes it structurally hard for a caller to confuse "the HTTP/transport failed"
 * ({@link TransportFailure}) with "the provider may have processed the payment"
 * ({@link PendingReconciliation}). Only {@link TransportFailure} can ever be a candidate for retry,
 * and even then only when the request provably never left the client.</p>
 *
 * @param <T> the success payload type (e.g. {@code BanxicoMessage}, {@code ClientePerfil}).
 */
public sealed interface PaymentOperationResult<T>
        permits Success, PendingReconciliation, Rejected, TransportFailure {

    /** True when a usable value is present. */
    default boolean isSuccess() {
        return this instanceof Success<T>;
    }

    /**
     * Whether the SDK is permitted to retry the operation that produced this outcome.
     * Success, Rejected and PendingReconciliation are unconditionally false.
     * TransportFailure's value is a lower bound; the authoritative decision also depends on the
     * operation kind and lives in {@link com.tupago.sdk.core.retry.RetryClassifier}.
     */
    boolean retryAllowed();
}
