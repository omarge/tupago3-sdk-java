package com.tupago.sdk.core.model;

/** Query the institution catalog by claveInstitucion (SDK-01, capability C6). */
public record GetCatalogoRequest(String claveInstitucion) {
    public GetCatalogoRequest {
        if (claveInstitucion == null || claveInstitucion.isBlank()) {
            throw new IllegalArgumentException("GetCatalogoRequest.claveInstitucion must not be blank");
        }
    }

    public static GetCatalogoRequest of(String claveInstitucion) {
        return new GetCatalogoRequest(claveInstitucion);
    }
}
