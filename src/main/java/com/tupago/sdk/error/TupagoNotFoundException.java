package com.tupago.sdk.error;

import io.grpc.Status;

/** Absent result (backend -> NOT_FOUND). Never auto-retried. */
public class TupagoNotFoundException extends TupagoException {
    public TupagoNotFoundException(Status status) {
        super(status, "NOT_FOUND: " + status.getDescription(), false, false);
    }
}
