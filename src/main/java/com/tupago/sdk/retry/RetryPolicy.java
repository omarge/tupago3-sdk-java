package com.tupago.sdk.retry;

import com.tupago.sdk.error.TupagoException;

/**
 * Decides whether (and how) an operation that failed with a {@link TupagoException} may be
 * retried. This is the single seam that enforces gate G4 (no automatic retry for CreateQr /
 * CreateTelefonico) and gate G6 (TUPAGO_PENDING_RECONCILIATION never retried).
 */
public interface RetryPolicy {

    /**
     * @param attempt zero-based attempt index of the failure just observed
     * @param error   the error thrown by the failed attempt
     * @return {@code true} if another attempt should be made
     */
    boolean shouldRetry(int attempt, TupagoException error);
}
