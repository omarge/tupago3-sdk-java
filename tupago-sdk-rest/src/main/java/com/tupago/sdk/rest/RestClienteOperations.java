package com.tupago.sdk.rest;

import com.tupago.sdk.core.model.ClientePerfil;
import com.tupago.sdk.core.model.GetClienteRequest;
import com.tupago.sdk.core.result.PaymentOperationResult;
import com.tupago.sdk.core.result.Success;
import com.tupago.sdk.core.client.ClienteOperations;
import com.tupago.sdk.rest.internal.RestResponseDto;
import com.tupago.sdk.rest.internal.RestTransport;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * REST implementation of the owned client-profile capability (SDK-02), bound to {@link ClienteOperations}.
 *
 * <p>Uses the OWNER-SCOPED path {@code GET /api/clientes/{id}} (backend
 * {@code AuthorizedClienteResolver.requireOwnedCliente}); the backend fails closed with 404 for non-owned
 * ids, which the adapter maps to {@code NotFound}. The full {@code catalogoInstitucion} object is not
 * returned by this endpoint (only {@code claveInstitucion}); it is left null in {@link ClientePerfil}
 * and can be resolved separately via {@code CatalogoOperations}.</p>
 */
final class RestClienteOperations implements ClienteOperations {

    private final RestTransport transport;
    private final ObjectMapper mapper;

    RestClienteOperations(RestTransport transport, ObjectMapper mapper) {
        this.transport = transport;
        this.mapper = mapper;
    }

    @Override
    public PaymentOperationResult<ClientePerfil> getCliente(GetClienteRequest request) {
        String path = RestPaths.GET_CLIENTE + request.id();
        return transport.exchange(
                com.tupago.sdk.core.retry.OperationKind.GET_CLIENTE,
                "GET", path, null, null,
                (node, status) -> {
                    RestResponseDto.ClienteDto c = mapper.convertValue(node, RestResponseDto.ClienteDto.class);
                    return new Success<>(new ClientePerfil(
                            c.id(), c.nombre(), c.claveInstitucion(), c.key(), null));
                });
    }
}
