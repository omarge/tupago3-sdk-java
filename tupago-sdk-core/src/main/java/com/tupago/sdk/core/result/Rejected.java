package com.tupago.sdk.core.result;

import com.tupago.sdk.core.error.PaymentError;

/** Domain (non-transport) failure: validation, idempotency conflict, policy, auth, not-found, internal. Never retryable. */
public record Rejected<T>(PaymentError error) implements PaymentOperationResult<T> {
    public Rejected {
        if (error == null) {
            throw new IllegalArgumentException("Rejected.error must not be null");
        }
    }

    @Override
    public boolean retryAllowed() {
        return false;
    }
}
