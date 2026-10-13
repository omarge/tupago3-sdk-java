package com.tupago.sdk.core.model;

import com.tupago.sdk.core.idempotency.IdempotencyKey;
import java.math.BigDecimal;
import java.util.Objects;

/**
 * Immutable create-telefonico request (SDK-01, canonical capability C2).
 *
 * <p>The modern, safe API REQUIRES an explicit {@link IdempotencyKey}. See {@link CreateQrRequest}
 * for the legacy/unsafe contract.</p>
 */
public record CreateTelefonicoRequest(
        String key,
        BigDecimal monto,
        String concepto,
        Integer referenciaNumerica,
        String numeroCelular,
        Integer vigencia,
        IdempotencyKey idempotencyKey) {

    public CreateTelefonicoRequest {
        Objects.requireNonNull(monto, "CreateTelefonicoRequest.monto must not be null");
        Objects.requireNonNull(idempotencyKey, "CreateTelefonicoRequest.idempotencyKey must not be null");
    }

    public static CreateTelefonicoRequest of(String key, BigDecimal monto, String concepto,
                                            Integer referenciaNumerica, String numeroCelular,
                                            Integer vigencia, IdempotencyKey idempotencyKey) {
        return new CreateTelefonicoRequest(key, monto, concepto, referenciaNumerica, numeroCelular,
                vigencia, idempotencyKey);
    }

    @Deprecated(since = "SDK-01", forRemoval = true)
    public static CreateTelefonicoRequest legacyUnsafe(String key, BigDecimal monto, String concepto,
                                                       Integer referenciaNumerica, String numeroCelular,
                                                       Integer vigencia) {
        return new CreateTelefonicoRequest(key, monto, concepto, referenciaNumerica, numeroCelular,
                vigencia, IdempotencyKey.UNSAFE_LEGACY);
    }

    public boolean isLegacyUnsafe() {
        return idempotencyKey.isLegacyUnsafe();
    }
}
