package com.tupago.sdk.core.idempotency;

import java.util.Objects;
import java.util.UUID;

/**
 * Strongly-typed client-supplied idempotency key (SDK-01, resolves gap G4).
 *
 * <p>Backend contract (SDK-00): idempotency is honored ONLY via an explicit client-supplied key.
 * The backend namespaces it {@code CODI:<key>} and the true dedup natural key is
 * {@code (clienteKey, externalPaymentKey)}. A missing key is NOT an error server-side: the backend
 * generates a per-request {@code LEGACY:<uuid>} so two identical legacy payments are treated as
 * SEPARATE operations. Therefore the SDK MUST make the key first-class and MUST NEVER auto-retry a
 * create that used {@link #UNSAFE_LEGACY}.</p>
 *
 * <p>Design rules:
 * <ul>
 *   <li>Non-null, non-blank, length-limited ({@link #MAX_LENGTH}).</li>
 *   <li>{@link #generate()} uses {@link UUID#randomUUID()} (cryptographically sufficient randomness).</li>
 *   <li>The SDK NEVER regenerates a key when repeating the same logical business payment; persisting and
 *       reusing the key across attempts is the caller's/business-workflow responsibility. The backend
 *       remains the authority for dedup.</li>
 *   <li>No local DB, no authoritative cache, no automatic filesystem persistence is created by the SDK.</li>
 * </ul>
 */
public final class IdempotencyKey {

    public static final int MAX_LENGTH = 255;

    private static final String LEGACY_SENTINEL = "﻿__TUPAGO_UNSAFE_LEGACY__﻿";

    /** Sentinel for the "no client key" path: the backend falls back to a per-request key; retries forbidden. */
    public static final IdempotencyKey UNSAFE_LEGACY = new IdempotencyKey(LEGACY_SENTINEL, true);

    private final String value;
    private final boolean legacy;

    private IdempotencyKey(String value, boolean legacy) {
        this.value = value;
        this.legacy = legacy;
    }

    /** Parse and validate a caller-supplied key. */
    public static IdempotencyKey of(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("IdempotencyKey must not be null");
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("IdempotencyKey must not be blank");
        }
        if (trimmed.length() > MAX_LENGTH) {
            throw new IllegalArgumentException(
                    "IdempotencyKey length " + trimmed.length() + " exceeds maximum " + MAX_LENGTH);
        }
        return new IdempotencyKey(trimmed, false);
    }

    /** Generate a fresh, cryptographically-random key. Callers must persist/reuse it across retries. */
    public static IdempotencyKey generate() {
        return of(UUID.randomUUID().toString());
    }

    /** Raw key value (without any {@code CODI:} namespace the backend adds). Empty for {@link #UNSAFE_LEGACY}. */
    public String value() {
        return legacy ? "" : value;
    }

    /** True for the no-key sentinel; transports MUST omit the idempotency header/arg and forbid auto-retry. */
    public boolean isLegacyUnsafe() {
        return legacy;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof IdempotencyKey other)) {
            return false;
        }
        return legacy == other.legacy && Objects.equals(value, other.value);
    }

    @Override
    public int hashCode() {
        return Objects.hash(value, legacy);
    }

    @Override
    public String toString() {
        // An idempotency key is NOT a secret; it is a caller-chosen correlation id. Safe to render.
        return legacy ? "IdempotencyKey[UNSAFE_LEGACY]" : "IdempotencyKey[" + value + "]";
    }
}
