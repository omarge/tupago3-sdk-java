package com.tupago.sdk.graphql;

import com.tupago.sdk.core.error.PaymentError;
import com.tupago.sdk.core.model.CatalogoInstitucion;
import com.tupago.sdk.core.model.ClientePerfil;
import com.tupago.sdk.core.model.GetClienteRequest;
import com.tupago.sdk.core.result.PaymentOperationResult;
import com.tupago.sdk.core.result.Rejected;
import com.tupago.sdk.core.result.Success;
import com.tupago.sdk.core.client.ClienteOperations;
import com.tupago.sdk.graphql.internal.GraphqlQuery;
import com.tupago.sdk.graphql.internal.GraphqlTransport;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * GraphQL implementation of the owned client-profile capability (SDK-03), bound to {@link ClienteOperations}.
 *
 * <p>Uses the OWNER-SCOPED {@code cliente(id: ID!)} query (backend resolves the single owned cliente by
 * JWT; a non-owned or unknown id yields {@code null} -> {@link PaymentError.NotFound}). The full
 * {@code catalogoInstitucion} object is returned by this capability and mapped as well.</p>
 */
final class GraphqlClienteOperations implements ClienteOperations {

    private final GraphqlTransport transport;

    GraphqlClienteOperations(GraphqlTransport transport) {
        this.transport = transport;
    }

    @Override
    public PaymentOperationResult<ClientePerfil> getCliente(GetClienteRequest request) {
        Map<String, Object> variables = new LinkedHashMap<>();
        variables.put("id", String.valueOf(request.id()));
        return transport.exchange(
                com.tupago.sdk.core.retry.OperationKind.GET_CLIENTE,
                GraphqlQuery.GET_CLIENTE, GraphqlQuery.GET_CLIENTE_DOC, variables,
                (data, status) -> {
                    JsonNode root = data.path("cliente");
                    if (root.isMissingNode() || root.isNull()) {
                        return new Rejected<>(new PaymentError.NotFound("Cliente not found for id " + request.id()));
                    }
                    JsonNode c = root.path("cliente");
                    if (c.isMissingNode() || c.isNull()
                            || c.path("id").isMissingNode() || c.path("id").isNull()
                            || c.path("nombre").isMissingNode() || c.path("nombre").isNull()
                            || c.path("claveInstitucion").isMissingNode() || c.path("claveInstitucion").isNull()
                            || c.path("key").isMissingNode() || c.path("key").isNull()) {
                        throw new IllegalStateException("GetCliente 'cliente' missing required fields");
                    }
                    JsonNode cat = root.path("catalogoInstitucion");
                    CatalogoInstitucion catalogo = null;
                    if (!cat.isMissingNode() && !cat.isNull()) {
                        if (cat.path("id").isMissingNode() || cat.path("claveInstitucion").isMissingNode()
                                || cat.path("nombreCorto").isMissingNode()) {
                            throw new IllegalStateException(
                                    "GetCliente 'catalogoInstitucion' missing required fields");
                        }
                        // G3: a present but non-numeric nested catalog id must fail closed, never yield id=null.
                        Long catId = parseLong(cat.path("id"));
                        if (catId == null) {
                            throw new IllegalStateException("GetCliente 'catalogoInstitucion.id' must be a numeric id");
                        }
                        catalogo = new CatalogoInstitucion(
                                catId,
                                cat.path("claveInstitucion").asText(),
                                cat.path("nombreCorto").asText());
                    }
                    // G3: a present but non-numeric cliente id must fail closed, never yield id=null.
                    Long cid = parseLong(c.path("id"));
                    if (cid == null) {
                        throw new IllegalStateException("GetCliente 'cliente.id' must be a numeric id");
                    }
                    return new Success<>(new ClientePerfil(
                            cid, c.path("nombre").asText(),
                            c.path("claveInstitucion").asText(), c.path("key").asText(), catalogo));
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
