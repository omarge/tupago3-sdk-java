package com.tupago.sdk.graphql;

import com.tupago.sdk.core.banxico.BanxicoMessage;
import com.tupago.sdk.core.error.PaymentError;
import com.tupago.sdk.core.model.CreateQrRequest;
import com.tupago.sdk.core.model.CreateTelefonicoRequest;
import com.tupago.sdk.core.model.GetQrStatusRequest;
import com.tupago.sdk.core.model.GetTelefonicoStatusRequest;
import com.tupago.sdk.core.result.PaymentOperationResult;
import com.tupago.sdk.core.result.Rejected;
import com.tupago.sdk.core.result.Success;
import com.tupago.sdk.core.client.PaymentOperations;
import com.tupago.sdk.graphql.internal.GraphqlQuery;
import com.tupago.sdk.graphql.internal.GraphqlRequestDto;
import com.tupago.sdk.graphql.internal.GraphqlTransport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * GraphQL implementation of the four payment capabilities (SDK-03): CreateQr (C1), CreateTelefonico (C2),
 * GetQrStatus (C3), GetTelefonicoStatus (C4), bound to the transport-neutral {@link PaymentOperations}.
 *
 * <p>Financial-safety invariants preserved (enforced by {@link GraphqlTransport} + {@link
 * com.tupago.sdk.core.retry.RetryClassifier}):
 * <ul>
 *   <li>Creates NEVER blind-retry (semantic decision owned solely by the core RetryClassifier).</li>
 *   <li>HTTP 200 + {@code errors:[{code: PAYMENT_PENDING_RECONCILIATION}]} -> {@link
 *       com.tupago.sdk.core.result.PendingReconciliation} (NEVER retry / resubmit).</li>
 *   <li>Raw Banxico payload preserved via {@link BanxicoMessage}; known shapes validated strictly and
 *       malformed/shape-violating bodies fail closed (B3 / B21).</li>
 * </ul>
 */
final class GraphqlPaymentOperations implements PaymentOperations {

    private final GraphqlTransport transport;
    private final ObjectMapper mapper;

    GraphqlPaymentOperations(GraphqlTransport transport, ObjectMapper mapper) {
        this.transport = transport;
        this.mapper = mapper;
    }

    @Override
    public PaymentOperationResult<BanxicoMessage> createQr(CreateQrRequest request) {
        Map<String, Object> variables = new LinkedHashMap<>();
        variables.put("input", GraphqlRequestDto.PagoQrInput.from(request));
        variables.put("idempotencyKey", idempotencyValue(request.idempotencyKey()));
        return transport.exchange(
                com.tupago.sdk.core.retry.OperationKind.CREATE_QR,
                GraphqlQuery.CREATE_QR, GraphqlQuery.CREATE_QR_DOC, variables,
                (data, status) -> {
                    // G1: the GraphQL mutation returns the backend MsjPagoQrResponse object DIRECTLY
                    // (JSON scalar pass-through). There is NO REST-style wrapper (mensaje/emisor/banco).
                    // Source of truth: TupagoGraphQlController.createPagoQr() -> createAndSendMsjPagoQR().cast(Object.class).
                    JsonNode node = data.path("createPagoQr");
                    if (absent(node)) {
                        throw new IllegalStateException("CreateQr response missing 'createPagoQr' data");
                    }
                    if (!node.isObject()) {
                        // Reject scalars, strings and arrays so an arbitrary payload can never be a success.
                        throw new IllegalStateException(
                                "CreateQr 'createPagoQr' must be a non-null JSON object (MsjPagoQrResponse)");
                    }
                    // Minimum authoritative shape: at least one known MsjPagoQrResponse field must be present
                    // (TYP / ic / v / CRY). An empty or unrelated object is not a valid payload.
                    boolean hasKnown = node.has("TYP") || node.has("ic") || node.has("v") || node.has("CRY");
                    if (!hasKnown) {
                        throw new IllegalStateException(
                                "CreateQr 'createPagoQr' is not a valid MsjPagoQrResponse (missing TYP/ic/v/CRY)");
                    }
                    // All known typed fields (TYP, v.DEV, ic.IDC/SER/ENC, CRY) AND unknown fields are preserved
                    // via BanxicoMessage.fromJsonNode.
                    return new Success<>(BanxicoMessage.fromJsonNode(node));
                });
    }

    @Override
    public PaymentOperationResult<BanxicoMessage> createTelefonico(CreateTelefonicoRequest request) {
        Map<String, Object> variables = new LinkedHashMap<>();
        variables.put("input", GraphqlRequestDto.PagoTelefonicoInput.from(request));
        variables.put("idempotencyKey", idempotencyValue(request.idempotencyKey()));
        return transport.exchange(
                com.tupago.sdk.core.retry.OperationKind.CREATE_TELEFONICO,
                GraphqlQuery.CREATE_TELEFONICO, GraphqlQuery.CREATE_TELEFONICO_DOC, variables,
                (data, status) -> {
                    // data.createPagoTelefonico is the MsjPagoTelefonicoResponseDTO JSON.
                    // detalleResultado is an authoritative textual raw Banxico JSON string (B11).
                    JsonNode node = data.path("createPagoTelefonico");
                    if (absent(node)) {
                        throw new IllegalStateException("CreateTelefonico response missing 'createPagoTelefonico' data");
                    }
                    JsonNode detalle = node.path("detalleResultado");
                    if (detalle.isMissingNode() || detalle.isNull()) {
                        throw new IllegalStateException("CreateTelefonico response missing 'detalleResultado' field");
                    }
                    if (!detalle.isTextual()) {
                        throw new IllegalStateException(
                                "CreateTelefonico 'detalleResultado' must be a JSON string (raw Banxico payload)");
                    }
                    return new Success<>(BanxicoMessage.fromJson(detalle.asText()));
                });
    }

    @Override
    public PaymentOperationResult<BanxicoMessage> getQrStatus(GetQrStatusRequest request) {
        Map<String, Object> variables = new LinkedHashMap<>();
        variables.put("idc", request.idc());
        variables.put("idCuenta", Long.toString(request.idCuenta()));
        return transport.exchange(
                com.tupago.sdk.core.retry.OperationKind.GET_QR_STATUS,
                GraphqlQuery.GET_QR_STATUS, GraphqlQuery.GET_QR_STATUS_DOC, variables,
                (data, status) -> {
                    // data.pagoQrEstatusByCuenta is a JSON array of transaction details (canonical C3 ByCuenta contract).
                    JsonNode node = data.path("pagoQrEstatusByCuenta");
                    if (absent(node) || !node.isArray()) {
                        throw new IllegalStateException("GetQrStatus payload must be a JSON array");
                    }
                    return new Success<>(BanxicoMessage.fromJsonNode(node));
                });
    }

    @Override
    public PaymentOperationResult<BanxicoMessage> getTelefonicoStatus(GetTelefonicoStatusRequest request) {
        Map<String, Object> variables = new LinkedHashMap<>();
        variables.put("idc", request.idc());
        variables.put("idCuenta", Long.toString(request.idCuenta()));
        return transport.exchange(
                com.tupago.sdk.core.retry.OperationKind.GET_TELEFONICO_STATUS,
                GraphqlQuery.GET_TELEFONICO_STATUS, GraphqlQuery.GET_TELEFONICO_STATUS_DOC, variables,
                (data, status) -> {
                    // data.pagoTelefonicoEstatusByCuenta is { detalleResultado: {Banxico OBJECT}, edoPet }.
                    JsonNode node = data.path("pagoTelefonicoEstatusByCuenta");
                    if (absent(node)) {
                        throw new IllegalStateException(
                                "GetTelefonicoStatus response missing 'pagoTelefonicoEstatusByCuenta' data");
                    }
                    JsonNode detalle = node.path("detalleResultado");
                    if (detalle.isMissingNode() || detalle.isNull()) {
                        throw new IllegalStateException("GetTelefonicoStatus response missing 'detalleResultado' field");
                    }
                    if (!detalle.isObject()) {
                        throw new IllegalStateException(
                                "GetTelefonicoStatus 'detalleResultado' must be a JSON object");
                    }
                    // Normalize the single Banxico object to a one-element transaction list for shape
                    // consistency with QR status. All raw fields are preserved inside the wrapped node.
                    return new Success<>(BanxicoMessage.fromJsonNode(mapper.createArrayNode().add(detalle)));
                });
    }

    private static String idempotencyValue(com.tupago.sdk.core.idempotency.IdempotencyKey key) {
        // Always present in variables (nullable arg). Legacy/unsafe -> null so the backend generates a
        // per-request key; the SDK MUST NOT auto-retry such creates (SDK-01 G4).
        return key.isLegacyUnsafe() ? null : key.value();
    }

    private static boolean absent(JsonNode n) {
        return n.isMissingNode() || n.isNull();
    }
}
