package com.tupago.sdk.security;

import io.grpc.ChannelCredentials;
import io.grpc.Grpc;
import io.grpc.InsecureChannelCredentials;
import io.grpc.ManagedChannelBuilder;
import io.grpc.TlsChannelCredentials;
import java.io.ByteArrayInputStream;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.file.Paths;
import java.util.Objects;

/**
 * Builds gRPC channels with the same FAIL-CLOSED posture as the backend's
 * {@code GrpcTransportSecurity}: plaintext is permitted ONLY to a loopback target; any
 * non-loopback target REQUIRES TLS (gate G3/G8). The SDK never opens an unencrypted,
 * off-host financial transport.
 */
public final class ChannelSecurity {

    private ChannelSecurity() {}

    /** @return true if the host resolves to a loopback address (127.0.0.1 / ::1 / localhost). */
    public static boolean isLoopback(String host) {
        if (host == null || host.isBlank()) {
            return false;
        }
        // Strip IPv6 brackets / ports defensively.
        String h = host;
        if (h.startsWith("[")) {
            int end = h.indexOf(']');
            if (end > 0) {
                h = h.substring(1, end);
            }
        } else if (h.contains(":")) {
            h = h.substring(0, h.indexOf(':'));
        }
        try {
            return InetAddress.getByName(h).isLoopbackAddress();
        } catch (UnknownHostException e) {
            return false;
        }
    }

    /**
     * Plaintext channel for LOCAL development only (loopback target). Fails closed if the host is
     * not loopback.
     */
    public static ManagedChannelBuilder<?> plaintextLoopback(String target) {
        if (!isLoopback(hostOf(target))) {
            throw new IllegalStateException(
                    "Plaintext channel refused: target '" + target + "' is not loopback (gate G8). Use TLS.");
        }
        return Grpc.newChannelBuilder(target, InsecureChannelCredentials.create())
                .maxInboundMessageSize(8 * 1024 * 1024);
    }

    /** TLS channel for any target (required for non-loopback). */
    public static ManagedChannelBuilder<?> tls(String target, TlsConfig tls) {
        Objects.requireNonNull(tls, "tls config required for non-loopback target");
        TlsChannelCredentials.Builder b = TlsChannelCredentials.newBuilder();
        try {
            if (tls.useSystemTrust()) {
                // default builder already uses system trust
            } else if (tls.trustCertChainPem() != null) {
                b.trustManager(new ByteArrayInputStream(tls.trustCertChainPem()));
            } else if (tls.trustCertChainPath() != null) {
                b.trustManager(new java.io.File(tls.trustCertChainPath()));
            } else {
                throw new IllegalStateException("TlsConfig has no usable trust material");
            }
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Failed to load TLS trust material: " + e.getMessage(), e);
        }
        ChannelCredentials creds = b.build();
        return Grpc.newChannelBuilder(target, creds).maxInboundMessageSize(8 * 1024 * 1024);
    }

    /**
     * Convenience: choose transport by target. Loopback -> plaintext (local dev); otherwise -> TLS.
     */
    public static ManagedChannelBuilder<?> forTarget(String target, TlsConfig tls) {
        if (isLoopback(hostOf(target))) {
            return plaintextLoopback(target);
        }
        return tls(target, tls);
    }

    private static String hostOf(String target) {
        if (target == null) {
            return "";
        }
        // authority form "host:port" or "dns:///host:port" or "ipv6:[::1]:port"
        String t = target;
        int schemeIdx = t.indexOf("://");
        if (schemeIdx >= 0) {
            t = t.substring(schemeIdx + 3);
        }
        if (t.startsWith("/")) {
            t = t.substring(1);
        }
        if (t.startsWith("[")) {
            int end = t.indexOf(']');
            return end > 0 ? t.substring(1, end) : t;
        }
        int colon = t.indexOf(':');
        return colon > 0 ? t.substring(0, colon) : t;
    }
}
