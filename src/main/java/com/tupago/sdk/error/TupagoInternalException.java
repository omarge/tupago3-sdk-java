package com.tupago.sdk.error;

import io.grpc.Status;

/** Unexpected backend failure (backend -> INTERNAL). Never auto-retried by the SDK. */
public class TupagoInternalException extends TupagoException {
    public TupagoInternalException(Status status) {
        super(status, "INTERNAL: " + status.getDescription(), false, false);
    }
}
