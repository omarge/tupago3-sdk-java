package com.tupago.sdk.error;

import io.grpc.Status;

/** Idempotency / policy revision conflict (backend -> ALREADY_EXISTS). Never auto-retried. */
public class TupagoAlreadyExistsException extends TupagoException {
    public TupagoAlreadyExistsException(Status status) {
        super(status, "ALREADY_EXISTS: " + status.getDescription(), false, false);
    }
}
