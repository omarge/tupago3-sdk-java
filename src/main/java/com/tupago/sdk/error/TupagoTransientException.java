package com.tupago.sdk.error;

import io.grpc.Status;

/** Transient transport condition (UNAVAILABLE / DEADLINE_EXCEEDED). Retryable for reads only. */
public class TupagoTransientException extends TupagoException {
    public TupagoTransientException(Status status) {
        super(status, status.getCode() + ": " + status.getDescription(), true, false);
    }
}
