package com.tupago.sdk.core.model;

/** Query the owned client profile by id (SDK-01, capability C5). */
public record GetClienteRequest(long id) {
    public static GetClienteRequest of(long id) {
        return new GetClienteRequest(id);
    }
}
