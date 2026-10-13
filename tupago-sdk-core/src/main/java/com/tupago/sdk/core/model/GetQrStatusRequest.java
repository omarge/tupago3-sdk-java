package com.tupago.sdk.core.model;

/** Query a QR payment status, keyed by Banxico IDC (SDK-01, capability C3). */
public record GetQrStatusRequest(String idc, Long idCuenta) {
    public GetQrStatusRequest {
        if (idc == null || idc.isBlank()) {
            throw new IllegalArgumentException("GetQrStatusRequest.idc must not be blank");
        }
    }

    public static GetQrStatusRequest of(String idc) {
        return new GetQrStatusRequest(idc, null);
    }

    public static GetQrStatusRequest of(String idc, Long idCuenta) {
        return new GetQrStatusRequest(idc, idCuenta);
    }
}
