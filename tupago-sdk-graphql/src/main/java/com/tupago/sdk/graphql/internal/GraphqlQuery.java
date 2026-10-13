package com.tupago.sdk.graphql.internal;

/**
 * Authoritative GraphQL operation documents for the six TUPAGO3 capabilities (SDK-03).
 *
 * <p>Every caller-supplied value flows through the operation's {@code variables} map — never by string
 * concatenation into the query source. This is the primary GraphQL-injection defense (SDK-03, section 6 / 25).
 * The field selection for the {@code JSON}-typed mutations/queries (create + status) is intentionally empty:
 * a JSON scalar returns its raw value without sub-selections.</p>
 */
public final class GraphqlQuery {

    private GraphqlQuery() {
    }

    public static final String CREATE_QR = "CreatePagoQr";
    public static final String CREATE_TELEFONICO = "CreatePagoTelefonico";
    public static final String GET_QR_STATUS = "GetQrStatus";
    public static final String GET_TELEFONICO_STATUS = "GetTelefonicoStatus";
    public static final String GET_CLIENTE = "GetCliente";
    public static final String GET_CATALOGO = "GetCatalogo";

    // ---- Mutations / queries (query source is constant; data arrives via `variables`) ----

    public static final String CREATE_QR_DOC = "mutation CreatePagoQr($input: PagoQrInput!, $idempotencyKey: String) {"
            + "  createPagoQr(input: $input, idempotencyKey: $idempotencyKey)"
            + "}";

    public static final String CREATE_TELEFONICO_DOC = "mutation CreatePagoTelefonico($input: PagoTelefonicoInput!, $idempotencyKey: String) {"
            + "  createPagoTelefonico(input: $input, idempotencyKey: $idempotencyKey)"
            + "}";

    public static final String GET_QR_STATUS_DOC = "query GetQrStatus($idc: String!) {"
            + "  pagoQrEstatusUsuario(idc: $idc)"
            + "}";

    public static final String GET_TELEFONICO_STATUS_DOC = "query GetTelefonicoStatus($idc: String!) {"
            + "  pagoTelefonicoEstatusUsuario(idc: $idc)"
            + "}";

    public static final String GET_CLIENTE_DOC = "query GetCliente($id: ID!) {"
            + "  cliente(id: $id) {"
            + "    cliente { id nombre claveInstitucion key }"
            + "    catalogoInstitucion { id claveInstitucion nombreCorto }"
            + "  }"
            + "}";

    public static final String GET_CATALOGO_DOC = "query GetCatalogo($claveInstitucion: String!) {"
            + "  catalogoInstitucion(claveInstitucion: $claveInstitucion) {"
            + "    id claveInstitucion nombreCorto"
            + "  }"
            + "}";
}
