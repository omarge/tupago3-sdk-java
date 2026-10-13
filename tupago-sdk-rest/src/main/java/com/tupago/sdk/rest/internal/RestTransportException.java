package com.tupago.sdk.rest.internal;

import com.tupago.sdk.core.error.PaymentError;

/**
 * Internal signal marking a transport-level failure that occurred before/while sending the HTTP request.
 * Carries the {@link PaymentError.TransportUnavailable} and the {@code mayHaveBeenSent} evidence used by
 * {@link com.tupago.sdk.core.retry.RetryClassifier} to decide retry posture.
 */
public final class RestTransportException extends Exception {

    private final PaymentError.TransportUnavailable error;
    private final boolean mayHaveBeenSent;

    RestTransportException(PaymentError.TransportUnavailable error, boolean mayHaveBeenSent, Throwable cause) {
        super(cause);
        this.error = error;
        this.mayHaveBeenSent = mayHaveBeenSent;
    }

    PaymentError.TransportUnavailable error() {
        return error;
    }

    boolean mayHaveBeenSent() {
        return mayHaveBeenSent;
    }
}
