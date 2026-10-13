package com.tupago.sdk.core.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class TimeoutConfigTest {

    @Test
    void defaults_areValid() {
        TimeoutConfig d = TimeoutConfig.defaults();
        assertThat(d.connectMillis()).isEqualTo(5_000);
        assertThat(d.requestMillis()).isEqualTo(30_000);
        assertThat(d.readMillis()).isEqualTo(30_000);
        assertThat(d.createOperationMillis()).isEqualTo(30_000);
        assertThat(d.connect()).isEqualTo(Duration.ofSeconds(5));
        assertThat(d.createOperation()).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void rejectsNegativeAndOverMax() {
        assertThatThrownBy(() -> new TimeoutConfig(-1, 1, 1, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TimeoutConfig(1, TimeoutConfig.MAX_MILLIS + 1, 1, 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void withers_returnNewValidInstances() {
        TimeoutConfig d = TimeoutConfig.defaults();
        TimeoutConfig c = d.withConnectMillis(2_000).withCreateOperationMillis(45_000);
        assertThat(c.connectMillis()).isEqualTo(2_000);
        assertThat(c.createOperationMillis()).isEqualTo(45_000);
        // original unchanged (immutability)
        assertThat(d.connectMillis()).isEqualTo(5_000);
    }
}
