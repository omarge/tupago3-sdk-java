package com.tupago.sdk.core.banxico;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Transport-neutral model of a raw Banxico payload (SDK-01, resolves gap G3).
 *
 * <p>Strategy (per SDK-00 / section 15): <b>typed known fields + raw payload preservation</b>.
 * Known fields are extracted from the observed backend DTOs so the SDK can branch on them safely;
 * the full {@link #raw()} JSON tree is retained so unknown/forward-compatible fields survive a
 * round-trip and are never silently dropped.</p>
 *
 * <p>Known typed fields are grounded in source (no invented fields):
 * <ul>
 *   <li>Create response ~ backend {@code MsjPagoQrResponse}: {@code TYP}, {@code v.DEV}, {@code ic.IDC},
 *       {@code ic.SER}, {@code ic.ENC}. Note {@code ic.IDC} is the Banxico IDC used for status queries.</li>
 *   <li>Status response ~ backend {@code EstatusMsjQrResponse}: a list of transactions with
 *       {@code id, cc, mt, hs, hp, e(estado), cr} (see {@link BanxicoTransaction}).</li>
 * </ul>
 *
 * <p>Immutable and thread-safe. {@link #rawJson()} round-trips the original bytes.</p>
 */
public final class BanxicoMessage {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final JsonNode raw;
    private final String rawJson;

    private BanxicoMessage(JsonNode raw, String rawJson) {
        this.raw = raw;
        this.rawJson = rawJson;
    }

    /** Parse a JSON payload string. Throws {@link BanxicoPayloadException} on invalid JSON. */
    public static BanxicoMessage fromJson(String json) {
        if (json == null) {
            throw new IllegalArgumentException("BanxicoMessage json must not be null");
        }
        try {
            JsonNode node = MAPPER.readTree(json);
            return new BanxicoMessage(node, json);
        } catch (Exception e) {
            throw new BanxicoPayloadException("Failed to parse Banxico payload", e);
        }
    }

    /** Wrap an already-parsed JSON tree, preserving a canonical serialized form. */
    public static BanxicoMessage fromJsonNode(JsonNode node) {
        Objects.requireNonNull(node, "BanxicoMessage node must not be null");
        try {
            return new BanxicoMessage(node, MAPPER.writeValueAsString(node));
        } catch (Exception e) {
            throw new BanxicoPayloadException("Failed to serialize Banxico payload", e);
        }
    }

    /** The full parsed JSON tree, including all unknown/forward-compatible fields. */
    public JsonNode raw() {
        return raw;
    }

    /** Canonical JSON serialization — round-trips the original payload without data loss. */
    public String rawJson() {
        return rawJson;
    }

    // ---- Typed known fields (create response, backend MsjPagoQrResponse) ----

    /** Banxico IDC ({@code ic.IDC}) — the key for subsequent status queries. */
    public String icIdc() {
        return textAt("ic", "IDC");
    }

    public Integer typ() {
        return intAt("TYP");
    }

    public String deviceDev() {
        return textAt("v", "DEV");
    }

    public Integer icSer() {
        return integerAt("ic", "SER");
    }

    public String icEnc() {
        return textAt("ic", "ENC");
    }

    // ---- Typed known fields (status response, backend EstatusMsjQrResponse) ----

    /** Transaction details. Handles both a top-level JSON array and an object with a {@code transactions} array. */
    public List<BanxicoTransaction> transactions() {
        if (raw.isArray()) {
            List<BanxicoTransaction> out = new ArrayList<>(raw.size());
            raw.forEach(n -> out.add(BanxicoTransaction.from(n)));
            return Collections.unmodifiableList(out);
        }
        JsonNode tx = raw.path("transactions");
        if (tx.isArray()) {
            List<BanxicoTransaction> out = new ArrayList<>(tx.size());
            tx.forEach(n -> out.add(BanxicoTransaction.from(n)));
            return Collections.unmodifiableList(out);
        }
        return Collections.emptyList();
    }

    private String textAt(String parent, String field) {
        JsonNode node = raw.path(parent).path(field);
        return node.isMissingNode() ? null : node.asText();
    }

    private Integer integerAt(String parent, String field) {
        JsonNode node = raw.path(parent).path(field);
        return node.isMissingNode() || node.isNull() ? null : node.asInt();
    }

    private Integer intAt(String field) {
        JsonNode node = raw.path(field);
        return node.isMissingNode() ? null : node.asInt();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof BanxicoMessage other)) {
            return false;
        }
        return Objects.equals(rawJson, other.rawJson);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(rawJson);
    }

    @Override
    public String toString() {
        // The raw payload may contain PII; do not echo it. Reveal only shape + size.
        return "BanxicoMessage[bytes=" + rawJson.length() + "]";
    }
}
