package com.tupago.sdk.error;

import io.grpc.Status;

/**
 * Unknown financial outcome for a CREATE operation. A transient transport error
 * (UNAVAILABLE / DEADLINE_EXCEEDED) observed on CreateQr / CreateTelefonico means the server may
 * or may not have persisted the payment — the result is UNKNOWN. Because blind resubmission is
 * financially dangerous, this is deliberately NON-retryable (gate G4) and signals the caller to
 * (a) re-invoke with the SAME idempotency_key so the server dedupes, or (b) call Get*Status.
 */
public class TupagoUnknownOutcomeException extends TupagoException {
    public TupagoUnknownOutcomeException(Status status) {
        super(status, "UNKNOWN_OUTCOME: " + status.getCode() + ": " + status.getDescription()
                + " — do not blind-resubmit; reuse the same idempotency_key or query status",
                false, false);
    }
}
