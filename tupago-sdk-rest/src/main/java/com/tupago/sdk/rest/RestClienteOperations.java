package com.tupago.sdk.rest;

import com.tupago.sdk.core.model.ClientePerfil;
import com.tupago.sdk.core.model.CatalogoInstitucion;
import com.tupago.sdk.core.model.GetCatalogoRequest;
import com.tupago.sdk.core.result.Rejected;
import com.tupago.sdk.core.error.PaymentError;
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
 * returned by this endpoint (only {@code claveInstitucion}); this adapter retrieves the
 * institution catalog separately and fails closed if the composition cannot be completed.</p>
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
        PaymentOperationResult<ClientePerfil> clienteResult = transport.exchange(
                com.tupago.sdk.core.retry.OperationKind.GET_CLIENTE,
                "GET", path, null, null,
            (node, status) -> {
                RestResponseDto.ClienteDto c = mapper.convertValue(node, RestResponseDto.ClienteDto.class);
                if (c.id() == null || c.nombre() == null || c.claveInstitucion() == null || c.key() == null) {
                    throw new IllegalStateException("GetCliente response missing required fields "
                            + "(id, nombre, claveInstitucion, key)");
                }
                return new Success<>(new ClientePerfil(
                        c.id(), c.nombre(), c.claveInstitucion(), c.key(), null));
            });
        if (!(clienteResult instanceof Success<ClientePerfil> clienteSuccess)) {
            return clienteResult;
        }
        ClientePerfil cliente = clienteSuccess.value();
        PaymentOperationResult<CatalogoInstitucion> catalogoResult =
                new RestCatalogoOperations(transport, mapper).getCatalogo(
                        GetCatalogoRequest.of(cliente.claveInstitucion()));
        if (catalogoResult instanceof Success<CatalogoInstitucion> catalogoSuccess) {
            CatalogoInstitucion catalogo = catalogoSuccess.value();
            if (!cliente.claveInstitucion().equals(catalogo.claveInstitucion())) {
                return new Rejected<>(new PaymentError.InternalFailure(
                        "GetCliente catalogo claveInstitucion mismatch"));
            }
            return new Success<>(new ClientePerfil(cliente.id(), cliente.nombre(),
                    cliente.claveInstitucion(), cliente.key(), catalogo));
        }
        if (catalogoResult instanceof Rejected<CatalogoInstitucion> rejected) {
            return new Rejected<>(rejected.error());
        }
        if (catalogoResult instanceof com.tupago.sdk.core.result.TransportFailure<CatalogoInstitucion> failed) {
            return new com.tupago.sdk.core.result.TransportFailure<>(failed.error(), failed.mayHaveBeenSent());
        }
        return new Rejected<>(new PaymentError.InternalFailure("GetCliente catalogo composition incomplete"));
    }
}
