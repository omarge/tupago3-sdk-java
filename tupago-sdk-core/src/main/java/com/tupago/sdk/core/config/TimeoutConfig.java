package com.tupago.sdk.core.config;

import java.time.Duration;

/**
 * Transport-neutral timeout/deadline policy (SDK-01, resolves gap G1).
 *
 * <p>Timeouts are surfaced explicitly so a transport adapter can apply them and, crucially, so the
 * SDK can distinguish a {@code create} timeout that occurred <em>after</em> the request may have been
 * sent (which must route to reconciliation, never a blind retry) from one that provably happened
 * before any byte left the client.</p>
 *
 * <p>The defaults below are a PROVISIONAL engineering recommendation only. They are NOT derived from a
 * documented backend contract (none was found in source). They exist so the SDK has sane, configurable
 * starting values; production tuning is a deployment decision. See the SDK-01 report (G1).</p>
 */
public record TimeoutConfig(
        long connectMillis,
        long requestMillis,
        long readMillis,
        long createOperationMillis) {

    /** Hard ceiling (10 minutes) to prevent absurd/accidental configurations. */
    public static final long MAX_MILLIS = 600_000L;

    public TimeoutConfig {
        requireNonNegative("connectMillis", connectMillis);
        requireNonNegative("requestMillis", requestMillis);
        requireNonNegative("readMillis", readMillis);
        requireNonNegative("createOperationMillis", createOperationMillis);
    }

    private static void requireNonNegative(String name, long value) {
        if (value < 0) {
            throw new IllegalArgumentException("TimeoutConfig." + name + " must be >= 0, was " + value);
        }
        if (value > MAX_MILLIS) {
            throw new IllegalArgumentException(
                    "TimeoutConfig." + name + " must be <= " + MAX_MILLIS + " ms, was " + value);
        }
    }

    /**
     * Provisional recommended defaults. NOT a backend contract.
     * connect=5s, request/deadline=30s, read=30s, create-operation=30s.
     */
    public static TimeoutConfig defaults() {
        return new TimeoutConfig(5_000L, 30_000L, 30_000L, 30_000L);
    }

    public Duration connect() {
        return Duration.ofMillis(connectMillis);
    }

    public Duration request() {
        return Duration.ofMillis(requestMillis);
    }

    public Duration read() {
        return Duration.ofMillis(readMillis);
    }

    public Duration createOperation() {
        return Duration.ofMillis(createOperationMillis);
    }

    public TimeoutConfig withConnectMillis(long millis) {
        return new TimeoutConfig(millis, requestMillis, readMillis, createOperationMillis);
    }

    public TimeoutConfig withRequestMillis(long millis) {
        return new TimeoutConfig(connectMillis, millis, readMillis, createOperationMillis);
    }

    public TimeoutConfig withReadMillis(long millis) {
        return new TimeoutConfig(connectMillis, requestMillis, millis, createOperationMillis);
    }

    public TimeoutConfig withCreateOperationMillis(long millis) {
        return new TimeoutConfig(connectMillis, requestMillis, readMillis, millis);
    }
}
