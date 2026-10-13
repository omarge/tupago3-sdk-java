package com.tupago.sdk.retry;

import com.tupago.sdk.error.TupagoException;

/**
 * Never retries. Used for the financial create operations (CreateQr / CreateTelefonico) so the
 * SDK can never resubmit a payment automatically (gate G4). The caller is responsible for
 * deciding what to do next (e.g. query status with the same idempotency key).
 *
 * @deprecated legacy retry seam; the NEW core-facing gRPC transport uses
 *             {@code com.tupago.sdk.core.retry.RetryClassifier} + core {@code RetryPolicy}.
 */
@Deprecated(since = "SDK-04", forRemoval = true)
public final class NoRetryPolicy implements RetryPolicy {

    public static final NoRetryPolicy INSTANCE = new NoRetryPolicy();

    private NoRetryPolicy() {}

    @Override
    public boolean shouldRetry(int attempt, TupagoException error) {
        return false;
    }
}
