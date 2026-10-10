package com.tupago.sdk.error;

import io.grpc.Status;

/**
 * Non-transient unknown-remote-state (backend -> ABORTED, carries
 * TUPAGO_PENDING_RECONCILIATION). The caller MUST query status, never resubmit (gate G6).
 */
public class TupagoAbortedException extends TupagoException {
    public TupagoAbortedException(Status status) {
        super(status, "ABORTED: " + status.getDescription(), false, true);
    }
}
