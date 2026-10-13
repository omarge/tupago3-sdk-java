package com.tupago.sdk.rest;

import com.tupago.sdk.core.model.CatalogoInstitucion;
import com.tupago.sdk.core.model.GetCatalogoRequest;
import com.tupago.sdk.core.result.PaymentOperationResult;
import com.tupago.sdk.core.result.Success;
import com.tupago.sdk.core.client.CatalogoOperations;
import com.tupago.sdk.rest.internal.RestResponseDto;
import com.tupago.sdk.rest.internal.RestTransport;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * REST implementation of the institution-catalog capability (SDK-02), bound to {@link CatalogoOperations}.
 *
 * <p>Uses {@code GET /api/catalogo-institucions/clave/{claveInstitucion}} and maps the backend
 * {@code CatalogoInstitucionDTO} ({@code id, claveInstitucion, nombreCorto}) onto the transport-neutral
 * {@link CatalogoInstitucion}.</p>
 */
final class RestCatalogoOperations implements CatalogoOperations {

    private final RestTransport transport;
    private final ObjectMapper mapper;

    RestCatalogoOperations(RestTransport transport, ObjectMapper mapper) {
        this.transport = transport;
        this.mapper = mapper;
    }

    @Override
    public PaymentOperationResult<CatalogoInstitucion> getCatalogo(GetCatalogoRequest request) {
        String path = RestPaths.GET_CATALOGO + request.claveInstitucion();
        return transport.exchange(
                com.tupago.sdk.core.retry.OperationKind.GET_CATALOGO,
                "GET", path, null, null,
                (node, status) -> {
                    RestResponseDto.CatalogoInstitucionDto c =
                            mapper.convertValue(node, RestResponseDto.CatalogoInstitucionDto.class);
                    return new Success<>(new CatalogoInstitucion(
                            c.id(), c.claveInstitucion(), c.nombreCorto()));
                });
    }
}
