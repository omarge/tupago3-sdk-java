package com.tupago.sdk.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChannelSecurityTest {

    @Test
    void loopbackDetection() {
        assertThat(ChannelSecurity.isLoopback("127.0.0.1")).isTrue();
        assertThat(ChannelSecurity.isLoopback("::1")).isTrue();
        assertThat(ChannelSecurity.isLoopback("localhost")).isTrue();
        assertThat(ChannelSecurity.isLoopback("[::1]:9090")).isTrue();
        assertThat(ChannelSecurity.isLoopback("example.com")).isFalse();
        assertThat(ChannelSecurity.isLoopback("10.0.0.5")).isFalse();
    }

    @Test
    void plaintextRefusedForNonLoopback() {
        assertThatThrownBy(() -> ChannelSecurity.plaintextLoopback("example.com:443"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not loopback");
    }

    @Test
    void plaintextAllowedForLoopback() {
        assertThat(ChannelSecurity.plaintextLoopback("127.0.0.1:9090")).isNotNull();
    }

    @Test
    void tlsChannelBuilderProducedForNonLoopback() {
        assertThat(ChannelSecurity.tls("example.com:443", TlsConfig.systemTrust())).isNotNull();
    }

    @Test
    void forTargetHonorsExplicitTlsOnLoopback() {
        // Explicit TLS must be honored even on loopback (cert env serves gRPC TLS on
        // 127.0.0.1:29091). No silent plaintext downgrade.
        assertThat(ChannelSecurity.forTarget("127.0.0.1:29091", TlsConfig.systemTrust())).isNotNull();
        assertThat(ChannelSecurity.forTarget("localhost:29091", TlsConfig.systemTrust())).isNotNull();
    }

    @Test
    void forTargetPlaintextOnlyForLoopbackWithoutTls() {
        assertThat(ChannelSecurity.forTarget("127.0.0.1:9090", null)).isNotNull();
        assertThatThrownBy(() -> ChannelSecurity.forTarget("example.com:443", null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("TLS required");
    }
}
