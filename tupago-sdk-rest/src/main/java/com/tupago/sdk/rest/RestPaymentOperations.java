package com.tupago.sdk.rest;

import com.tupago.sdk.core.banxico.BanxicoMessage;
import com.tupago.sdk.core.model.CreateQrRequest;
import com.tupago.sdk.core.model.CreateTelefonicoRequest;
import com.tupago.sdk.core.model.GetQrStatusRequest;
import com.tupago.sdk.core.model.GetTelefonicoStatusRequest;
import com.tupago.sdk.core.result.PaymentOperationResult;
import com.tupago.sdk.core.result.PendingReconciliation;
import com.tupago.sdk.core.result.Success;
import com.tupago.sdk.core.client.PaymentOperations;
import com.tupago.sdk.rest.internal.RestRequestDto;
import com.tupago.sdk.rest.internal.RestTransport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * REST implementation of the four payment capabilities (SDK-02-R1): CreateQr, CreateTelefonico,
 * GetQrStatus, GetTelefonicoStatus, bound to the transport-neutral {@link PaymentOperations} contract.
 *
 * <p>Financial-safety invariants preserved (enforced by {@link RestTransport} + {@link RestClassifier}
 * in the transport layer):
 * <ul>
 *   <li>Creates NEVER blind-retry (semantic decision owned solely by the core {@code RetryClassifier}).</li>
 *   <li>HTTP 202 with authoritative problem type {@code payment-pending-reconciliation} ->
 *       {@link PendingReconciliation} (NEVER retry); any other 202 contract fails closed.</li>
 *   <li>Raw Banxico payload preserved via {@link BanxicoMessage} for both create and status responses.</li>
 *   <li>Every 2xx body is parsed strictly and validated for minimum structural contract; malformed or
 *       shape-violating bodies fail closed as {@code Rejected(InternalFailure)} (B3).</li>
 * </ul>
 */
final class RestPaymentOperations implements PaymentOperations {

    private final RestTransport transport;
    private final ObjectMapper mapper;

    RestPaymentOperations(RestTransport transport, ObjectMapper mapper) {
        this.transport = transport;
        this.mapper = mapper;
    }

    @Override
    public PaymentOperationResult<BanxicoMessage> createQr(CreateQrRequest request) {
        RestRequestDto.CreateQrRequestDto body = RestRequestDto.CreateQrRequestDto.from(request);
        return transport.exchange(
                com.tupago.sdk.core.retry.OperationKind.CREATE_QR,
                "POST", RestPaths.CREATE_QR, body, request.idempotencyKey(),
                (node, status) -> {
                    if (status == 202) {
                        return requirePendingReconciliation(node);
                    }
                    // Backend CreateQr success: { "mensaje": {MsjPagoQrResponse OBJECT}, "emisor", "banco" }.
                    // 'mensaje' is a JSON OBJECT, never a string (B1). Preserve its raw shape via JsonNode.
                    JsonNode mensaje = node.path("mensaje");
                    if (mensaje.isMissingNode() || mensaje.isNull() || !mensaje.isObject()) {
                        throw new IllegalStateException(
                                "CreateQr 'mensaje' must be a non-null JSON object (MsjPagoQrResponse)");
                    }
                    return new Success<>(BanxicoMessage.fromJsonNode(mensaje));
                });
    }

    @Override
    public PaymentOperationResult<BanxicoMessage> createTelefonico(CreateTelefonicoRequest request) {
        RestRequestDto.CreateTelefonicoRequestDto body = RestRequestDto.CreateTelefonicoRequestDto.from(request);
        return transport.exchange(
                com.tupago.sdk.core.retry.OperationKind.CREATE_TELEFONICO,
                "POST", RestPaths.CREATE_TELEFONICO, body, request.idempotencyKey(),
                (node, status) -> {
                    if (status == 202) {
                        return requirePendingReconciliation(node);
                    }
                    // Backend CreateTelefonico success: { "detalleResultado": "<raw Banxico JSON STRING>", "edoPet" }.
                    // detalleResultado is authoritative textual JSON (B11); reject non-textual shapes.
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
        RestRequestDto.QrStatusRequestDto body = RestRequestDto.QrStatusRequestDto.from(request);
        return transport.exchange(
                com.tupago.sdk.core.retry.OperationKind.GET_QR_STATUS,
                "POST", RestPaths.GET_QR_STATUS, body, null,
                (node, status) -> {
                    // Backend QR status returns a top-level JSON array of transaction details.
                    if (!node.isArray()) {
                        throw new IllegalStateException("GetQrStatus payload must be a JSON array");
                    }
                    return new Success<>(BanxicoMessage.fromJsonNode(node));
                });
    }

    @Override
    public PaymentOperationResult<BanxicoMessage> getTelefonicoStatus(GetTelefonicoStatusRequest request) {
        RestRequestDto.TelefonicoStatusRequestDto body = RestRequestDto.TelefonicoStatusRequestDto.from(request);
        return transport.exchange(
                com.tupago.sdk.core.retry.OperationKind.GET_TELEFONICO_STATUS,
                "POST", RestPaths.GET_TELEFONICO_STATUS, body, null,
                (node, status) -> {
                    // Backend Telefonico status: { "detalleResultado": {Banxico OBJECT}, "edoPet" }.
                    JsonNode detalle = node.path("detalleResultado");
                    if (detalle.isMissingNode() || detalle.isNull()) {
                        throw new IllegalStateException("GetTelefonicoStatus response missing 'detalleResultado' field");
                    }
                    if (!detalle.isObject()) {
                        throw new IllegalStateException(
                                "GetTelefonicoStatus 'detalleResultado' must be a JSON object");
                    }
                    // Normalize the single Banxico object to a one-element transaction list for shape
                    // consistency with QR status. All raw fields (including unknown nested ones) are
                    // preserved inside the wrapped object node.
                    JsonNode normalized = mapper.createArrayNode().add(detalle);
                    return new Success<>(BanxicoMessage.fromJsonNode(normalized));
                });
    }

    /** Map an HTTP 202 to PendingReconciliation only when the problem type is the authoritative one (B16). */
    private static <T> PendingReconciliation<T> requirePendingReconciliation(JsonNode node) {
        JsonNode type = node.path("type");
        String t = type.isMissingNode() ? "" : type.asText();
        if (!t.toLowerCase().contains("payment-pending-reconciliation")) {
            throw new IllegalStateException("Unexpected 202 contract type: " + (t.isEmpty() ? "<none>" : t));
        }
        return new PendingReconciliation<>(PendingReconciliation.CODE, pendingMessage(node), null, null);
    }

    private static String pendingMessage(JsonNode node) {
        JsonNode detail = node.path("detail");
        if (!detail.isMissingNode() && !detail.isNull()) {
            return detail.asText();
        }
        JsonNode title = node.path("title");
        if (!title.isMissingNode() && !title.isNull()) {
            return title.asText();
        }
        return "Payment operation pending reconciliation";
    }
}
