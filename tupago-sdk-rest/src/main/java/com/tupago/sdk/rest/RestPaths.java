package com.tupago.sdk.rest;

/** Canonical REST endpoint paths (SDK-02, mapped against backend canonical anchor bbf06c02). */
final class RestPaths {

    private RestPaths() {
    }

    // Payments (owner-scoped "usuario" variants — see SDK-02 report section: ownership evidence).
    static final String CREATE_QR = "/api/payments/msjpagoqrusuario";
    static final String CREATE_TELEFONICO = "/api/payments/msjpagotelefonicousuario";
    static final String GET_QR_STATUS = "/api/payments/estatusmsjqrusuario";
    static final String GET_TELEFONICO_STATUS = "/api/payments/estatusmsjcobrotelefonicousuario";

    // Owned client profile (owner-scoped via AuthorizedClienteResolver.requireOwnedCliente).
    static final String GET_CLIENTE = "/api/clientes/";

    // Institution catalog (by Banxico clave).
    static final String GET_CATALOGO = "/api/catalogo-institucions/clave/";
}
