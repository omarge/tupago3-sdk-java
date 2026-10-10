package com.tupago.sdk.error;

import io.grpc.Status;

/** Missing/invalid bearer token (backend interceptor -> UNAUTHENTICATED). */
public class TupagoUnauthenticatedException extends TupagoException {
    public TupagoUnauthenticatedException(Status status) {
        super(status, "UNAUTHENTICATED: " + status.getDescription(), false, false);
    }
}
