package com.tupago.sdk.core.retry;

/** The six canonical TuPago3 payment capabilities (SDK-00). */
public enum OperationKind {
    CREATE_QR,
    CREATE_TELEFONICO,
    GET_QR_STATUS,
    GET_TELEFONICO_STATUS,
    GET_CLIENTE,
    GET_CATALOGO;

    /** True for the two charge-initiating operations, which MUST NOT be blindly retried. */
    public boolean isCreate() {
        return this == CREATE_QR || this == CREATE_TELEFONICO;
    }

    /** True for read-only operations, which may retry on a proven pre-execution transport failure. */
    public boolean isRead() {
        return !isCreate();
    }
}
