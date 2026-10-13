package com.tupago.sdk.core.model;

import com.tupago.sdk.core.idempotency.IdempotencyKey;
import java.math.BigDecimal;
import java.util.Objects;

/**
 * Immutable create-QR request (SDK-01, canonical capability C1).
 *
 * <p>The modern, safe API REQUIRES an explicit {@link IdempotencyKey}. Use {@link #legacyUnsafe} only
 * for pre-existing callers that cannot supply a key; that path makes the backend fall back to a
 * per-request key and the SDK MUST NOT auto-retry it.</p>
 */
public record CreateQrRequest(
        Long idCuenta,
        BigDecimal monto,
        String concepto,
        Integer referenciaNumerica,
        String nombreCliente,
        String email,
        Integer tipoPago,
        Integer vigencia,
        IdempotencyKey idempotencyKey) {

    public CreateQrRequest {
        Objects.requireNonNull(monto, "CreateQrRequest.monto must not be null");
        Objects.requireNonNull(idempotencyKey, "CreateQrRequest.idempotencyKey must not be null");
    }

    /** Safe factory: caller supplies a first-class idempotency key. */
    public static CreateQrRequest of(Long idCuenta, BigDecimal monto, String concepto,
                                     Integer referenciaNumerica, String nombreCliente, String email,
                                     Integer tipoPago, Integer vigencia, IdempotencyKey idempotencyKey) {
        return new CreateQrRequest(idCuenta, monto, concepto, referenciaNumerica, nombreCliente, email,
                tipoPago, vigencia, idempotencyKey);
    }

    /**
     * UNSAFE / LEGACY: no client idempotency key. The backend generates a per-request key; the SDK
     * MUST NOT auto-retry this operation. Marked for removal once all callers supply a key.
     */
    @Deprecated(since = "SDK-01", forRemoval = true)
    public static CreateQrRequest legacyUnsafe(Long idCuenta, BigDecimal monto, String concepto,
                                               Integer referenciaNumerica, String nombreCliente, String email,
                                               Integer tipoPago, Integer vigencia) {
        return new CreateQrRequest(idCuenta, monto, concepto, referenciaNumerica, nombreCliente, email,
                tipoPago, vigencia, IdempotencyKey.UNSAFE_LEGACY);
    }

    public boolean isLegacyUnsafe() {
        return idempotencyKey.isLegacyUnsafe();
    }
}
