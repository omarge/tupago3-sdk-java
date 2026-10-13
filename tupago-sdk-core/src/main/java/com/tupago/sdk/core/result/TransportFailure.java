package com.tupago.sdk.core.result;

import com.tupago.sdk.core.error.PaymentError;
import com.tupago.sdk.core.error.PaymentError.TransportUnavailable;

/**
 * Transport-level failure where it is uncertain whether the request reached the backend.
 *
 * <p>{@link #retryAllowed()} returns a NECESSARY-BUT-NOT-SUFFICIENT lower bound ({@code !mayHaveBeenSent}).
 * The authoritative decision also depends on the operation kind and lives in
 * {@link com.tupago.sdk.core.retry.RetryClassifier}: creates are NEVER blindly retried, even when
 * {@code mayHaveBeenSent == false}.</p>
 */
public record TransportFailure<T>(PaymentError error, boolean mayHaveBeenSent) implements PaymentOperationResult<T> {
    public TransportFailure {
        if (error == null) {
            throw new IllegalArgumentException("TransportFailure.error must not be null");
        }
        if (!(error instanceof TransportUnavailable)) {
            throw new IllegalArgumentException(
                    "TransportFailure must wrap a TransportUnavailable error, got " + error.category());
        }
    }

    @Override
    public boolean retryAllowed() {
        return !mayHaveBeenSent;
    }
}
