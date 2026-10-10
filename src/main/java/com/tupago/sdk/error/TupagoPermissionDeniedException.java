package com.tupago.sdk.error;

import io.grpc.Status;

/** Reserved cross-transport status (PERMISSION_DENIED). Never auto-retried. */
public class TupagoPermissionDeniedException extends TupagoException {
    public TupagoPermissionDeniedException(Status status) {
        super(status, "PERMISSION_DENIED: " + status.getDescription(), false, false);
    }
}
