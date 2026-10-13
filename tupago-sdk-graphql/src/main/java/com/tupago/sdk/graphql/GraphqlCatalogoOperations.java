package com.tupago.sdk.graphql;

import com.tupago.sdk.core.error.PaymentError;
import com.tupago.sdk.core.model.CatalogoInstitucion;
import com.tupago.sdk.core.model.GetCatalogoRequest;
import com.tupago.sdk.core.result.PaymentOperationResult;
import com.tupago.sdk.core.result.Rejected;
import com.tupago.sdk.core.result.Success;
import com.tupago.sdk.core.client.CatalogoOperations;
import com.tupago.sdk.graphql.internal.GraphqlQuery;
import com.tupago.sdk.graphql.internal.GraphqlTransport;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * GraphQL implementation of the institution-catalog capability (SDK-03), bound to {@link CatalogoOperations}.
 *
 * <p>Uses {@code catalogoInstitucion(claveInstitucion: String!)}. The clave is validated to be a safe
 * alphanumeric Banxico clave (B13, mirroring REST) before being placed in {@code variables} (never in the
 * query source). A null result -> {@link PaymentError.NotFound}; a shape-violating result fails closed.</p>
 */
final class GraphqlCatalogoOperations implements CatalogoOperations {

    private static final String CLAVE_PATTERN = "[A-Za-z0-9]+";

    private final GraphqlTransport transport;

    GraphqlCatalogoOperations(GraphqlTransport transport) {
        this.transport = transport;
    }

    @Override
    public PaymentOperationResult<CatalogoInstitucion> getCatalogo(GetCatalogoRequest request) {
        String clave = request.claveInstitucion();
        if (clave == null || clave.isEmpty() || !clave.matches(CLAVE_PATTERN)) {
            return new Rejected<>(new PaymentError.ValidationFailure(
                    "Invalid claveInstitucion: must be a non-empty alphanumeric Banxico clave"));
        }
        Map<String, Object> variables = new LinkedHashMap<>();
        variables.put("claveInstitucion", clave);
        return transport.exchange(
                com.tupago.sdk.core.retry.OperationKind.GET_CATALOGO,
                GraphqlQuery.GET_CATALOGO, GraphqlQuery.GET_CATALOGO_DOC, variables,
                (data, status) -> {
                    JsonNode node = data.path("catalogoInstitucion");
                    if (node.isMissingNode() || node.isNull()) {
                        return new Rejected<>(new PaymentError.NotFound(
                                "CatalogoInstitucion not found for clave " + clave));
                    }
                    if (node.path("id").isMissingNode() || node.path("claveInstitucion").isMissingNode()
                            || node.path("nombreCorto").isMissingNode()) {
                        throw new IllegalStateException(
                                "GetCatalogo response missing required fields (id, claveInstitucion, nombreCorto)");
                    }
                    // G3: a present but non-numeric id must fail closed, never yield id=null.
                    Long cid = parseLong(node.path("id"));
                    if (cid == null) {
                        throw new IllegalStateException("GetCatalogo 'id' must be a numeric id");
                    }
                    return new Success<>(new CatalogoInstitucion(
                            cid,
                            node.path("claveInstitucion").asText(),
                            node.path("nombreCorto").asText()));
                });
    }

    private static Long parseLong(JsonNode n) {
        if (n.isNumber()) {
            return n.longValue();
        }
        String s = n.asText();
        try {
            return s == null || s.isBlank() ? null : Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
