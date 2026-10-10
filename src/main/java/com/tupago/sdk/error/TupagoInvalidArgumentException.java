package com.tupago.sdk.error;

import io.grpc.Status;

/** Malformed request (backend -> INVALID_ARGUMENT). Never auto-retried. */
public class TupagoInvalidArgumentException extends TupagoException {
    public TupagoInvalidArgumentException(Status status) {
        super(status, "INVALID_ARGUMENT: " + status.getDescription(), false, false);
    }
}
