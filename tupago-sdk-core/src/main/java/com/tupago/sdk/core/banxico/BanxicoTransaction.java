package com.tupago.sdk.core.banxico;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * One transaction detail inside a Banxico status response (grounded in backend
 * {@code EstatusMsjQrResponse.TransactionDetail}: id, cc, mt, hs, hp, e(estado), cr, plus vendor/customer
 * info). Typed known fields are extracted; the full {@link #raw()} node is preserved so no data is lost.
 */
public record BanxicoTransaction(
        String id,
        String cc,
        Double mt,
        Long hs,
        Long hp,
        Integer estado,
        String cr,
        JsonNode raw) {

    static BanxicoTransaction from(JsonNode node) {
        return new BanxicoTransaction(
                node.path("id").asText(null),
                node.path("cc").asText(null),
                node.path("mt").isMissingNode() ? null : node.path("mt").asDouble(),
                node.path("hs").isMissingNode() ? null : node.path("hs").asLong(),
                node.path("hp").isMissingNode() ? null : node.path("hp").asLong(),
                node.path("e").isMissingNode() ? null : node.path("e").asInt(),
                node.path("cr").asText(null),
                node);
    }
}
