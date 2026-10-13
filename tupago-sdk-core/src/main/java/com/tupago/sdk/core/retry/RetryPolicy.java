package com.tupago.sdk.core.retry;

import java.time.Duration;

/**
 * Transport-neutral retry policy (SDK-01, section 13). Concrete retry execution belongs to the
 * transport adapters (SDK-02/03/04); this type only expresses intent.
 */
public interface RetryPolicy {
    /** Maximum number of attempts (1 means "no retry"). Must be >= 1. */
    int maxAttempts();

    /** Base delay between attempts. */
    Duration baseDelay();
}
