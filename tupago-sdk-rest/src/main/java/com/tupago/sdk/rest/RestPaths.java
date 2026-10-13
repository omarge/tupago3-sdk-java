package com.tupago.sdk.rest;

/** Canonical REST endpoint paths (SDK-02, mapped against backend canonical anchor bbf06c02). */
final class RestPaths {

    private RestPaths() {
    }

    // Payments — ByCuenta preferred when idCuenta is explicit (program law).
    // Usuario variants exist on backend but drop explicit ownership when idCuenta is known.
    static final String CREATE_QR = "/api/payments/msjpagoqrusuario";
    static final String CREATE_TELEFONICO = "/api/payments/msjpagotelefonico";
    static final String GET_QR_STATUS = "/api/payments/estatusmsjqr";
    static final String GET_TELEFONICO_STATUS = "/api/payments/estatusmsjcobrotelefonico";

    // Owned client profile (owner-scoped via AuthorizedClienteResolver.requireOwnedCliente).
    static final String GET_CLIENTE = "/api/clientes/";

    // Institution catalog (by Banxico clave).
    static final String GET_CATALOGO = "/api/catalogo-institucions/clave/";
}
