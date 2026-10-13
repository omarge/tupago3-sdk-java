package com.tupago.sdk.retry;

import com.tupago.sdk.error.TupagoException;

/**
 * Historical gRPC retry policy (legacy). Reconciled with the SDK-01 transport-neutral core
 * (SDK-04): the NEW core-facing gRPC transport uses {@code com.tupago.sdk.core.retry.RetryClassifier}
 * as the SINGLE semantic retry authority and {@code com.tupago.sdk.core.retry.RetryPolicy} for the
 * attempt bound / backoff. This legacy type remains only as a compatibility adapter for the
 * historical {@code TupagoPaymentClient}; do not use it as a second retry authority.
 *
 * @deprecated use {@code com.tupago.sdk.core.retry.RetryClassifier} + core {@code RetryPolicy}.
 */
@Deprecated(since = "SDK-04", forRemoval = true)
public interface RetryPolicy {

    /**
     * @param attempt zero-based attempt index of the failure just observed
     * @param error   the error thrown by the failed attempt
     * @return {@code true} if another attempt should be made
     */
    boolean shouldRetry(int attempt, TupagoException error);
}
