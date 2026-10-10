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
}
