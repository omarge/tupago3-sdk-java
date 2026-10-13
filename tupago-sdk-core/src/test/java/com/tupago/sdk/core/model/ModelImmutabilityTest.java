package com.tupago.sdk.core.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tupago.sdk.core.idempotency.IdempotencyKey;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class ModelImmutabilityTest {

    @Test
    void createQr_requiresIdempotencyKey() {
        IdempotencyKey key = IdempotencyKey.generate();
        CreateQrRequest req = CreateQrRequest.of(1L, new BigDecimal("100.00"), "concepto", 123, "Cliente", "a@b.c", 1, 30, key);
        assertThat(req.idempotencyKey()).isEqualTo(key);
        assertThat(req.monto()).isEqualByComparingTo("100.00");
        assertThat(req.isLegacyUnsafe()).isFalse();
    }

    @Test
    void createQr_rejectsNullMontoAndNullKey() {
        assertThatThrownBy(() -> CreateQrRequest.of(1L, null, "c", 1, "n", "e", 1, 1, IdempotencyKey.generate()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> CreateQrRequest.of(1L, new BigDecimal("1"), "c", 1, "n", "e", 1, 1, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @SuppressWarnings("deprecation")
    void legacyUnsafe_marksNoKeyAndForbidsRetry() {
        CreateQrRequest legacy = CreateQrRequest.legacyUnsafe(1L, new BigDecimal("1"), "c", 1, "n", "e", 1, 1);
        assertThat(legacy.isLegacyUnsafe()).isTrue();
        assertThat(legacy.idempotencyKey()).isEqualTo(IdempotencyKey.UNSAFE_LEGACY);
    }

    @Test
    void requests_areValueEqual() {
        IdempotencyKey k = IdempotencyKey.of("k1");
        CreateQrRequest a = CreateQrRequest.of(1L, new BigDecimal("5"), null, null, null, null, null, null, k);
        CreateQrRequest b = CreateQrRequest.of(1L, new BigDecimal("5"), null, null, null, null, null, null, k);
        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
    }

    @Test
    void getRequests_validateRequiredFields() {
        assertThatThrownBy(() -> new GetQrStatusRequest("  ", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GetCatalogoRequest(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(GetClienteRequest.of(7L).id()).isEqualTo(7L);
    }
}
