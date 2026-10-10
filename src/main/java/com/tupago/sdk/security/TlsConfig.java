package com.tupago.sdk.security;

import java.util.Objects;

/**
 * Operator-supplied TLS material for a non-loopback gRPC target. No secret is ever embedded in
 * the SDK or repo (gate G3); paths/certificates are provided at runtime by the calling
 * application (file paths, in-memory PEM, or a trust manager).
 */
public final class TlsConfig {

    private final String trustCertChainPath;
    private final byte[] trustCertChainPem;
    private final boolean useSystemTrust;

    private TlsConfig(String trustCertChainPath, byte[] trustCertChainPem, boolean useSystemTrust) {
        this.trustCertChainPath = trustCertChainPath;
        this.trustCertChainPem = trustCertChainPem;
        this.useSystemTrust = useSystemTrust;
    }

    /** Use the JVM's default CA trust store (typical for public CAs). */
    public static TlsConfig systemTrust() {
        return new TlsConfig(null, null, true);
    }

    /** Trust an explicit PEM certificate chain file. */
    public static TlsConfig trustCertChainFile(String path) {
        return new TlsConfig(Objects.requireNonNull(path, "path"), null, false);
    }

    /** Trust an explicit PEM certificate chain supplied in memory. */
    public static TlsConfig trustCertChainPem(byte[] pem) {
        return new TlsConfig(null, Objects.requireNonNull(pem, "pem"), false);
    }

    public String trustCertChainPath() {
        return trustCertChainPath;
    }

    public byte[] trustCertChainPem() {
        return trustCertChainPem;
    }

    public boolean useSystemTrust() {
        return useSystemTrust;
    }
}
