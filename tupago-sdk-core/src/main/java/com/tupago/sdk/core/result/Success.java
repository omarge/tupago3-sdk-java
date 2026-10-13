package com.tupago.sdk.core.result;

/** Successful outcome carrying the typed payload {@code value}. Never retryable. */
public record Success<T>(T value) implements PaymentOperationResult<T> {
    public Success {
        if (value == null) {
            throw new IllegalArgumentException("Success.value must not be null");
        }
    }

    @Override
    public boolean retryAllowed() {
        return false;
    }
}
