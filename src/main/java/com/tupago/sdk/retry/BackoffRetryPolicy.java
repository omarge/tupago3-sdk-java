package com.tupago.sdk.retry;

import com.tupago.sdk.error.TupagoException;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Capped exponential backoff with jitter, retrying ONLY genuinely transient transport errors
 * (UNAVAILABLE / DEADLINE_EXCEEDED, i.e. {@code TupagoException.isRetryable() == true}).
 * Used for idempotent read operations. It never retries create operations (callers must pass
 * {@link NoRetryPolicy} there) and never retries TUPAGO_PENDING_RECONCILIATION.
 *
 * @deprecated legacy retry seam; the NEW core-facing gRPC transport uses
 *             {@code com.tupago.sdk.core.retry.RetryClassifier} + core {@code RetryPolicy}.
 */
@Deprecated(since = "SDK-04", forRemoval = true)
public final class BackoffRetryPolicy implements RetryPolicy {

    private final int maxAttempts;
    private final long baseDelayMillis;
    private final long maxDelayMillis;

    public BackoffRetryPolicy(int maxAttempts, long baseDelayMillis, long maxDelayMillis) {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be >= 1");
        }
        this.maxAttempts = maxAttempts;
        this.baseDelayMillis = Math.max(1, baseDelayMillis);
        this.maxDelayMillis = Math.max(baseDelayMillis, maxDelayMillis);
    }

    public static BackoffRetryPolicy of(int maxAttempts) {
        return new BackoffRetryPolicy(maxAttempts, 200L, 2_000L);
    }

    @Override
    public boolean shouldRetry(int attempt, TupagoException error) {
        if (attempt >= maxAttempts - 1) {
            return false;
        }
        // Only retry if the error is explicitly marked retryable (transient transport).
        // TUPAGO_PENDING_RECONCILIATION / ALREADY_EXISTS / others are never retried.
        return error.isRetryable();
    }

    /** Computes the backoff delay (with jitter) before the given (zero-based) attempt. */
    public long delayMillis(int attempt) {
        long exp = (long) (baseDelayMillis * Math.pow(2, attempt));
        long capped = Math.min(exp, maxDelayMillis);
        long jitter = ThreadLocalRandom.current().nextLong(0, Math.max(1, capped / 2));
        return capped + jitter;
    }
}
