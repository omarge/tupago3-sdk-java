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
 * REST implementation of the four payment capabilities (SDK-02): CreateQr, CreateTelefonico, GetQrStatus,
 * GetTelefonicoStatus, bound to the transport-neutral {@link PaymentOperations} contract.
 *
 * <p>Financial-safety invariants preserved:
 * <ul>
 *   <li>Creates NEVER blind-retry (maxAttempts=1 in {@link RestTransport}; idempotency key forwarded).</li>
 *   <li>HTTP 202 / payment-pending-reconciliation -> {@link PendingReconciliation} (NEVER retry).</li>
 *   <li>Raw Banxico payload preserved via {@link BanxicoMessage} for both create and status responses.</li>
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
                        return new PendingReconciliation<BanxicoMessage>(
                                PendingReconciliation.CODE, pendingMessage(node), null, null);
                    }
                    JsonNode mensaje = node.path("mensaje");
                    if (mensaje.isMissingNode() || mensaje.isNull()) {
                        throw new IllegalStateException("CreateQr response missing 'mensaje' field");
                    }
                    return new Success<>(BanxicoMessage.fromJson(mensaje.asText()));
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
                        return new PendingReconciliation<BanxicoMessage>(
                                PendingReconciliation.CODE, pendingMessage(node), null, null);
                    }
                    JsonNode detalle = node.path("detalleResultado");
                    if (detalle.isMissingNode() || detalle.isNull()) {
                        throw new IllegalStateException("CreateTelefonico response missing 'detalleResultado' field");
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
                (node, status) -> new Success<>(BanxicoMessage.fromJsonNode(node)));
    }

    @Override
    public PaymentOperationResult<BanxicoMessage> getTelefonicoStatus(GetTelefonicoStatusRequest request) {
        RestRequestDto.TelefonicoStatusRequestDto body = RestRequestDto.TelefonicoStatusRequestDto.from(request);
        return transport.exchange(
                com.tupago.sdk.core.retry.OperationKind.GET_TELEFONICO_STATUS,
                "POST", RestPaths.GET_TELEFONICO_STATUS, body, null,
                (node, status) -> {
                    JsonNode detalle = node.path("detalleResultado");
                    if (detalle.isMissingNode() || detalle.isNull()) {
                        throw new IllegalStateException("GetTelefonicoStatus response missing 'detalleResultado' field");
                    }
                    // The telefonico status payload is a single Banxico object; normalize to a one-element
                    // transaction list so status queries have a consistent shape with QR status.
                    JsonNode normalized = detalle.isArray() ? detalle : mapper.createArrayNode().add(detalle);
                    return new Success<>(BanxicoMessage.fromJsonNode(normalized));
                });
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
