package com.tupago.sdk.grpc;

import com.tupago.sdk.core.error.PaymentError;
import com.tupago.sdk.core.model.CatalogoInstitucion;
import com.tupago.sdk.core.model.GetCatalogoRequest;
import com.tupago.sdk.core.result.PaymentOperationResult;
import com.tupago.sdk.core.result.Rejected;
import com.tupago.sdk.core.result.Success;
import com.tupago.sdk.core.client.CatalogoOperations;
import com.tupago.sdk.core.retry.OperationKind;
import com.tupago.sdk.grpc.internal.GrpcTransport;

/**
 * gRPC implementation of the institution-catalog capability (SDK-04), bound to {@link CatalogoOperations}.
 *
 * <p>Uses {@code catalogoInstitucion(claveInstitucion)}. The clave is validated to be a safe alphanumeric
 * Banxico clave before the RPC (never in the payload). A null/empty/invalid result yields
 * {@link PaymentError.NotFound}; proto3 scalar defaults ({@code id == 0}) denote an unset server-side null
 * mapping and are rejected fail-closed.</p>
 */
final class GrpcCatalogoOperations implements CatalogoOperations {

    private static final String CLAVE_PATTERN = "[A-Za-z0-9]+";

    private final GrpcTransport transport;

    GrpcCatalogoOperations(GrpcTransport transport) {
        this.transport = transport;
    }

    @Override
    public PaymentOperationResult<CatalogoInstitucion> getCatalogo(GetCatalogoRequest request) {
        String clave = request.claveInstitucion();
        if (clave == null || clave.isEmpty() || !clave.matches(CLAVE_PATTERN)) {
            return new Rejected<>(new PaymentError.ValidationFailure(
                    "Invalid claveInstitucion: must be a non-empty alphanumeric Banxico clave"));
        }
        com.tupago.sdk.grpc.v1.GetCatalogoRequest proto =
                com.tupago.sdk.grpc.v1.GetCatalogoRequest.newBuilder().setClaveInstitucion(clave).build();
        return transport.exchange(OperationKind.GET_CATALOGO, null,
                stub -> stub.getCatalogo(proto),
                resp -> {
                    com.tupago.sdk.grpc.v1.CatalogoInstitucion c = resp;
                    if (c == null || c.getId() == 0 || isBlank(c.getClaveInstitucion()) || isBlank(c.getNombreCorto())) {
                        throw new IllegalStateException("GetCatalogo response missing required fields / id=0");
                    }
                    return new Success<>(new CatalogoInstitucion(c.getId(), c.getClaveInstitucion(), c.getNombreCorto()));
                });
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
