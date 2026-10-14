package com.tupago.sdk.rest;

/** Canonical REST endpoint paths (SDK-02, mapped against backend canonical anchor bbf06c02). */
final class RestPaths {

    private RestPaths() {
    }

    // Payments — authority must match gRPC/GraphQL (resolveSingleOwnedCliente).
    // C2 MUST use the "usuario" variant. The non-usuario variant uses requireOwnedClienteByKey
    // and is a DIFFERENT authority contract.
    static final String CREATE_QR = "/api/payments/msjpagoqrusuario";
    static final String CREATE_TELEFONICO = "/api/payments/msjpagotelefonicousuario";

    // C3/C4 status: ByCuenta preferred when idCuenta is explicit.
    static final String GET_QR_STATUS = "/api/payments/estatusmsjqr";
    static final String GET_TELEFONICO_STATUS = "/api/payments/estatusmsjcobrotelefonico";

    // Owned client profile (owner-scoped via AuthorizedClienteResolver.requireOwnedCliente).
    static final String GET_CLIENTE = "/api/clientes/";

    // Institution catalog (by Banxico clave).
    static final String GET_CATALOGO = "/api/catalogo-institucions/clave/";
}
