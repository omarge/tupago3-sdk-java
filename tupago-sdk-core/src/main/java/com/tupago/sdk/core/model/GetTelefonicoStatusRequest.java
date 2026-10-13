package com.tupago.sdk.core.model;

/** Query a telefonico payment status, keyed by Banxico IDC (SDK-01, capability C4). */
public record GetTelefonicoStatusRequest(String idc, Long idCuenta) {
    public GetTelefonicoStatusRequest {
        if (idc == null || idc.isBlank()) {
            throw new IllegalArgumentException("GetTelefonicoStatusRequest.idc must not be blank");
        }
    }

    public static GetTelefonicoStatusRequest of(String idc) {
        return new GetTelefonicoStatusRequest(idc, null);
    }

    public static GetTelefonicoStatusRequest of(String idc, Long idCuenta) {
        return new GetTelefonicoStatusRequest(idc, idCuenta);
    }
}
