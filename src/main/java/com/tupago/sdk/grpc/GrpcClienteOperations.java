package com.tupago.sdk.grpc;

import com.tupago.sdk.core.error.PaymentError;
import com.tupago.sdk.core.model.CatalogoInstitucion;
import com.tupago.sdk.core.model.ClientePerfil;
import com.tupago.sdk.core.model.GetClienteRequest;
import com.tupago.sdk.core.result.PaymentOperationResult;
import com.tupago.sdk.core.result.Rejected;
import com.tupago.sdk.core.result.Success;
import com.tupago.sdk.core.client.ClienteOperations;
import com.tupago.sdk.core.retry.OperationKind;
import com.tupago.sdk.grpc.internal.GrpcTransport;

/**
 * gRPC implementation of the owned client-profile capability (SDK-04), bound to {@link ClienteOperations}.
 *
 * <p>Backend resolves the single OWNED cliente by JWT (owner-scoped). An unknown/absent cliente yields a
 * gRPC NOT_FOUND, which the transport maps to {@link PaymentError.NotFound}. Proto3 scalar defaults ({@code id
 * == 0}, empty strings) may denote an unset server-side null mapping; such structurally invalid data is
 * rejected fail-closed (never coerced into a legitimate {@link ClientePerfil}).</p>
 */
final class GrpcClienteOperations implements ClienteOperations {

    private final GrpcTransport transport;

    GrpcClienteOperations(GrpcTransport transport) {
        this.transport = transport;
    }

    @Override
    public PaymentOperationResult<ClientePerfil> getCliente(GetClienteRequest request) {
        com.tupago.sdk.grpc.v1.GetClienteRequest proto =
                com.tupago.sdk.grpc.v1.GetClienteRequest.newBuilder().setId(request.id()).build();
        return transport.exchange(OperationKind.GET_CLIENTE, null,
                stub -> stub.getCliente(proto),
                resp -> {
                    com.tupago.sdk.grpc.v1.ClientePerfil cp = resp;
                    com.tupago.sdk.grpc.v1.Cliente c = cp.getCliente();
                    if (c == null || c.getId() == 0 || isBlank(c.getNombre())
                            || isBlank(c.getClaveInstitucion()) || isBlank(c.getKey())) {
                        throw new IllegalStateException("GetCliente 'cliente' missing required fields / id=0");
                    }
                    com.tupago.sdk.grpc.v1.CatalogoInstitucion cat = cp.getCatalogoInstitucion();
                    CatalogoInstitucion coreCat = null;
                    if (cat != null) {
                        if (cat.getId() == 0 || isBlank(cat.getClaveInstitucion()) || isBlank(cat.getNombreCorto())) {
                            throw new IllegalStateException(
                                    "GetCliente 'catalogoInstitucion' missing required fields / id=0");
                        }
                        coreCat = new CatalogoInstitucion(cat.getId(), cat.getClaveInstitucion(), cat.getNombreCorto());
                    }
                    return new Success<>(new ClientePerfil(
                            c.getId(), c.getNombre(), c.getClaveInstitucion(), c.getKey(), coreCat));
                });
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
