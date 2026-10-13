package com.tupago.sdk.rest;

import com.tupago.sdk.core.error.PaymentError;
import com.tupago.sdk.core.model.CatalogoInstitucion;
import com.tupago.sdk.core.model.GetCatalogoRequest;
import com.tupago.sdk.core.result.PaymentOperationResult;
import com.tupago.sdk.core.result.Rejected;
import com.tupago.sdk.core.result.Success;
import com.tupago.sdk.core.client.CatalogoOperations;
import com.tupago.sdk.rest.internal.RestResponseDto;
import com.tupago.sdk.rest.internal.RestTransport;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * REST implementation of the institution-catalog capability (SDK-02-R1), bound to {@link CatalogoOperations}.
 *
 * <p>Uses {@code GET /api/catalogo-institucions/clave/{claveInstitucion}}. The clave is validated to be a
 * safe path segment (Banxico clave contract: bounded alphanumeric) so it cannot inject {@code /}, {@code ?},
 * {@code #} or other URI metacharacters (B13). A valid 2xx body is validated for the minimum required
 * fields; a shape-violating body fails closed (B3).</p>
 */
final class RestCatalogoOperations implements CatalogoOperations {

    private static final String CLAVE_PATTERN = "[A-Za-z0-9]+";

    private final RestTransport transport;
    private final ObjectMapper mapper;

    RestCatalogoOperations(RestTransport transport, ObjectMapper mapper) {
        this.transport = transport;
        this.mapper = mapper;
    }

    @Override
    public PaymentOperationResult<CatalogoInstitucion> getCatalogo(GetCatalogoRequest request) {
        String clave = request.claveInstitucion();
        if (clave == null || clave.isEmpty() || !clave.matches(CLAVE_PATTERN)) {
            return new Rejected<>(new PaymentError.ValidationFailure(
                    "Invalid claveInstitucion: must be a non-empty alphanumeric Banxico clave"));
        }
        // alnum-only clave is a safe URI path segment; no metacharacter injection possible.
        String path = RestPaths.GET_CATALOGO + clave;
        return transport.exchange(
                com.tupago.sdk.core.retry.OperationKind.GET_CATALOGO,
                "GET", path, null, null,
                (node, status) -> {
                    RestResponseDto.CatalogoInstitucionDto c =
                            mapper.convertValue(node, RestResponseDto.CatalogoInstitucionDto.class);
                    if (c.id() == null || c.claveInstitucion() == null || c.nombreCorto() == null) {
                        throw new IllegalStateException("GetCatalogo response missing required fields "
                                + "(id, claveInstitucion, nombreCorto)");
                    }
                    return new Success<>(new CatalogoInstitucion(
                            c.id(), c.claveInstitucion(), c.nombreCorto()));
                });
    }
}
