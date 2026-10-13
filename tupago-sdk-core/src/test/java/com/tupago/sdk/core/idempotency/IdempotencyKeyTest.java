package com.tupago.sdk.core.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class IdempotencyKeyTest {

    @Test
    void of_rejectsNullBlankAndOverlong() {
        assertThatThrownBy(() -> IdempotencyKey.of(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> IdempotencyKey.of("  ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> IdempotencyKey.of("a".repeat(256)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void of_trimsAndPreserves() {
        IdempotencyKey key = IdempotencyKey.of("  key-123  ");
        assertThat(key.value()).isEqualTo("key-123");
        assertThat(key.isLegacyUnsafe()).isFalse();
    }

    @Test
    void generate_isCryptoRandomAndDistinct() {
        IdempotencyKey a = IdempotencyKey.generate();
        IdempotencyKey b = IdempotencyKey.generate();
        assertThat(a.value()).isNotEqualTo(b.value());
        assertThat(a.value()).hasSize(36); // UUID
        assertThat(a.isLegacyUnsafe()).isFalse();
    }

    @Test
    void legacySentinel_isUnsafeAndHasEmptyValue() {
        IdempotencyKey legacy = IdempotencyKey.UNSAFE_LEGACY;
        assertThat(legacy.isLegacyUnsafe()).isTrue();
        assertThat(legacy.value()).isEmpty();
    }

    @Test
    void valueEqualityAndHashCode() {
        IdempotencyKey x = IdempotencyKey.of("same");
        IdempotencyKey y = IdempotencyKey.of("same");
        IdempotencyKey z = IdempotencyKey.of("diff");
        assertThat(x).isEqualTo(y).hasSameHashCodeAs(y);
        assertThat(x).isNotEqualTo(z);
    }

    @Test
    void toString_doesNotLeakSecretNature() {
        // An idempotency key is a correlation id, not a secret; rendering it is acceptable.
        assertThat(IdempotencyKey.of("abc").toString()).contains("abc");
        assertThat(IdempotencyKey.UNSAFE_LEGACY.toString()).contains("UNSAFE_LEGACY");
    }
}
