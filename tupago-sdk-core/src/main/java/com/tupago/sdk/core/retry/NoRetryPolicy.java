package com.tupago.sdk.core.retry;

import java.time.Duration;

/** No retry: a single attempt, zero backoff. The default for create operations. */
public final class NoRetryPolicy implements RetryPolicy {
    public static final NoRetryPolicy INSTANCE = new NoRetryPolicy();

    private NoRetryPolicy() {
    }

    @Override
    public int maxAttempts() {
        return 1;
    }

    @Override
    public Duration baseDelay() {
        return Duration.ZERO;
    }
}
