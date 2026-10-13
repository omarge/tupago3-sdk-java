package com.tupago.sdk.core.retry;

import java.time.Duration;

/**
 * Bounded backoff retry policy for read operations. Transport adapters apply it only when
 * {@link RetryClassifier} permits a retry (i.e. a proven pre-execution transport failure on a read).
 */
public record BackoffRetryPolicy(int maxAttempts, Duration baseDelay) implements RetryPolicy {
    public BackoffRetryPolicy {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be >= 1");
        }
        if (baseDelay == null || baseDelay.isNegative()) {
            throw new IllegalArgumentException("baseDelay must be non-negative");
        }
    }

    public static BackoffRetryPolicy of(int maxAttempts, Duration baseDelay) {
        return new BackoffRetryPolicy(maxAttempts, baseDelay);
    }
}
