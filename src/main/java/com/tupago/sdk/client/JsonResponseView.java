package com.tupago.sdk.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tupago.sdk.grpc.v1.JsonResponse;
import java.io.IOException;
import java.util.Objects;

/**
 * Convenience view over the raw Banxico {@link JsonResponse} returned by status/create RPCs.
 * The SDK never assumes a fixed JSON shape (contract: lossless passthrough, section 1/8 I7);
 * callers may read the raw string, parse to a tree, or bind to their own DTO.
 */
public final class JsonResponseView {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final JsonResponse response;

    public JsonResponseView(JsonResponse response) {
        this.response = Objects.requireNonNull(response, "response");
    }

    /** The raw JSON payload, exactly as returned by the backend. */
    public String json() {
        return response.getJson();
    }

    /** Parsed JSON tree (lazily parsed; throws if the payload is not valid JSON). */
    public JsonNode parsed() {
        try {
            return MAPPER.readTree(response.getJson());
        } catch (IOException e) {
            throw new IllegalStateException("JsonResponse payload is not valid JSON: " + e.getMessage(), e);
        }
    }

    /** Bind the raw JSON to a caller-supplied type. */
    public <T> T as(Class<T> type) {
        try {
            return MAPPER.readValue(response.getJson(), type);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to bind JsonResponse to " + type.getName() + ": " + e.getMessage(), e);
        }
    }

    public JsonResponse raw() {
        return response;
    }
}
