package com.tupago.sdk.grpc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tupago.sdk.core.banxico.BanxicoMessage;
import com.tupago.sdk.core.error.PaymentError;
import com.tupago.sdk.core.idempotency.IdempotencyKey;
import com.tupago.sdk.core.model.CreateQrRequest;
import com.tupago.sdk.core.model.CreateTelefonicoRequest;
import com.tupago.sdk.core.model.GetQrStatusRequest;
import com.tupago.sdk.core.model.GetTelefonicoStatusRequest;
import com.tupago.sdk.core.result.PaymentOperationResult;
import com.tupago.sdk.core.result.Rejected;
import com.tupago.sdk.core.result.Success;
import com.tupago.sdk.core.client.PaymentOperations;
import com.tupago.sdk.core.retry.OperationKind;
import com.tupago.sdk.grpc.internal.GrpcTransport;
import com.tupago.sdk.grpc.v1.JsonResponse;
import com.tupago.sdk.grpc.v1.PaymentServiceGrpc.PaymentServiceBlockingStub;

/**
 * gRPC implementation of the four payment capabilities (SDK-04): CreateQr (C1), CreateTelefonico (C2),
 * GetQrStatus (C3), GetTelefonicoStatus (C4), bound to the transport-neutral {@link PaymentOperations}.
 *
 * <p>Financial-safety invariants preserved (enforced by {@link GrpcTransport} + core
 * {@link com.tupago.sdk.core.retry.RetryClassifier}):
 * <ul>
 *   <li>Creates NEVER blind-retry (semantic decision owned solely by the core RetryClassifier).</li>
 *   <li>gRPC status RPCs are ACCOUNT-SCOPED: a null {@code idCuenta} is rejected locally as
 *       {@link PaymentError.ValidationFailure} BEFORE any RPC (no conversion to protobuf default 0).</li>
 *   <li>Backend {@code JsonResponse.json} carries the same Banxico payloads as the other transports:
 *       CreateQr -&gt; direct MsjPagoQrResponse object; CreateTelefonico -&gt; {@code detalleResultado} raw
 *       Banxico JSON string; status -&gt; JSON array / object. Parsed strictly; malformed JSON fails closed.</li>
 *   <li>PendingReconciliation (ABORTED + {@code TUPAGO_PENDING_RECONCILIATION}) -&gt; {@link
 *       com.tupago.sdk.core.result.PendingReconciliation} (NEVER retry / resubmit).</li>
 * </ul>
 */
final class GrpcPaymentOperations implements PaymentOperations {

    private final GrpcTransport transport;
    private final ObjectMapper mapper = new ObjectMapper();

    GrpcPaymentOperations(GrpcTransport transport) {
        this.transport = transport;
    }

    @Override
    public PaymentOperationResult<BanxicoMessage> createQr(CreateQrRequest request) {
        com.tupago.sdk.grpc.v1.CreateQrRequest.Builder b = com.tupago.sdk.grpc.v1.CreateQrRequest.newBuilder();
        if (request.idCuenta() != null) {
            b.setIdCuenta(request.idCuenta());
        }
        b.setMonto(request.monto().doubleValue());
        if (request.concepto() != null) {
            b.setConcepto(request.concepto());
        }
        if (request.referenciaNumerica() != null) {
            b.setReferenciaNumerica(request.referenciaNumerica());
        }
        if (request.nombreCliente() != null) {
            b.setNombreCliente(request.nombreCliente());
        }
        if (request.email() != null) {
            b.setEmail(request.email());
        }
        if (request.tipoPago() != null) {
            b.setTipoPago(request.tipoPago());
        }
        if (request.vigencia() != null) {
            b.setVigencia(request.vigencia());
        }
        b.setIdempotencyKey(idempotencyValue(request.idempotencyKey()));
        com.tupago.sdk.grpc.v1.CreateQrRequest proto = b.build();

        return transport.exchange(OperationKind.CREATE_QR, null,
                stub -> stub.createQr(proto),
                resp -> {
                    JsonNode node = parseJson(resp.getJson());
                    if (!node.isObject()) {
                        throw new IllegalStateException("CreateQr payload must be a JSON object (MsjPagoQrResponse)");
                    }
                    boolean hasKnown = node.has("TYP") || node.has("ic") || node.has("v") || node.has("CRY");
                    if (!hasKnown) {
                        throw new IllegalStateException(
                                "CreateQr payload is not a valid MsjPagoQrResponse (missing TYP/ic/v/CRY)");
                    }
                    return new Success<>(BanxicoMessage.fromJsonNode(node));
                });
    }

    @Override
    public PaymentOperationResult<BanxicoMessage> createTelefonico(CreateTelefonicoRequest request) {
        if (request.numeroCelular() != null && !request.numeroCelular().isBlank()
                && !request.numeroCelular().matches("\\d+")) {
            // Backend converts numero_celular to Long; a non-numeric value is objectively invalid and
            // must fail locally (no network call) rather than reaching a backend INVALID_ARGUMENT.
            return new Rejected<>(new PaymentError.ValidationFailure(
                    "CreateTelefonico numeroCelular must be numeric: " + request.numeroCelular()));
        }
        com.tupago.sdk.grpc.v1.CreateTelefonicoRequest.Builder b =
                com.tupago.sdk.grpc.v1.CreateTelefonicoRequest.newBuilder();
        b.setKey(request.key() == null ? "" : request.key());
        b.setMonto(request.monto().doubleValue());
        if (request.concepto() != null) {
            b.setConcepto(request.concepto());
        }
        if (request.referenciaNumerica() != null) {
            b.setReferenciaNumerica(request.referenciaNumerica());
        }
        if (request.numeroCelular() != null) {
            b.setNumeroCelular(request.numeroCelular());
        }
        if (request.vigencia() != null) {
            b.setVigencia(request.vigencia());
        }
        b.setIdempotencyKey(idempotencyValue(request.idempotencyKey()));
        com.tupago.sdk.grpc.v1.CreateTelefonicoRequest proto = b.build();

        return transport.exchange(OperationKind.CREATE_TELEFONICO, null,
                stub -> stub.createTelefonico(proto),
                resp -> {
                    JsonNode node = parseJson(resp.getJson());
                    if (!node.isObject()) {
                        throw new IllegalStateException("CreateTelefonico payload must be a JSON object");
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
        if (request.idCuenta() == null) {
            return new Rejected<>(new PaymentError.ValidationFailure(
                    "gRPC QR status is account-scoped: idCuenta is required (must not be null)"));
        }
        com.tupago.sdk.grpc.v1.GetStatusRequest proto =
                com.tupago.sdk.grpc.v1.GetStatusRequest.newBuilder()
                        .setIdc(request.idc()).setIdCuenta(request.idCuenta()).build();
        return transport.exchange(OperationKind.GET_QR_STATUS, request.idc(),
                stub -> stub.getQrStatus(proto),
                resp -> {
                    JsonNode node = parseJson(resp.getJson());
                    if (!node.isArray()) {
                        throw new IllegalStateException("GetQrStatus payload must be a JSON array");
                    }
                    return new Success<>(BanxicoMessage.fromJsonNode(node));
                });
    }

    @Override
    public PaymentOperationResult<BanxicoMessage> getTelefonicoStatus(GetTelefonicoStatusRequest request) {
        if (request.idCuenta() == null) {
            return new Rejected<>(new PaymentError.ValidationFailure(
                    "gRPC telefonico status is account-scoped: idCuenta is required (must not be null)"));
        }
        com.tupago.sdk.grpc.v1.GetStatusRequest proto =
                com.tupago.sdk.grpc.v1.GetStatusRequest.newBuilder()
                        .setIdc(request.idc()).setIdCuenta(request.idCuenta()).build();
        return transport.exchange(OperationKind.GET_TELEFONICO_STATUS, request.idc(),
                stub -> stub.getTelefonicoStatus(proto),
                resp -> {
                    JsonNode node = parseJson(resp.getJson());
                    if (!node.isObject()) {
                        throw new IllegalStateException("GetTelefonicoStatus payload must be a JSON object");
                    }
                    JsonNode detalle = node.path("detalleResultado");
                    if (detalle.isMissingNode() || detalle.isNull()) {
                        throw new IllegalStateException("GetTelefonicoStatus response missing 'detalleResultado' field");
                    }
                    if (!detalle.isObject()) {
                        throw new IllegalStateException("GetTelefonicoStatus 'detalleResultado' must be a JSON object");
                    }
                    return new Success<>(BanxicoMessage.fromJsonNode(mapper.createArrayNode().add(detalle)));
                });
    }

    private JsonNode parseJson(String json) {
        try {
            return mapper.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException("Malformed Banxico JSON payload", e);
        }
    }

    private static String idempotencyValue(IdempotencyKey key) {
        // Legacy/unsafe -> empty key so the backend follows its no-key behavior; the SDK MUST NOT
        // auto-retry such creates (SDK-01 G4). Never fabricate a replacement logical key.
        return key.isLegacyUnsafe() ? "" : key.value();
    }
}
