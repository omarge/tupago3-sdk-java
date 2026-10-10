package com.tupago.sdk.error;

import io.grpc.Status;

/** Limit policy not satisfied (backend -> FAILED_PRECONDITION). Never auto-retried. */
public class TupagoFailedPreconditionException extends TupagoException {
    public TupagoFailedPreconditionException(Status status) {
        super(status, "FAILED_PRECONDITION: " + status.getDescription(), false, false);
    }
}
