package com.tupago.sdk.client;

import com.tupago.sdk.auth.CredentialProvider;
import com.tupago.sdk.error.TupagoUnauthenticatedException;
import com.tupago.sdk.security.TlsConfig;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * REAL F03-R3 negative gate (invalid/absent bearer) against the isolated cert/loopback backend3.
 * Run in its OWN JVM/connection so it does not share the HTTP/2 transport with an authenticated
 * client (the backend scopes auth per-connection; this test owns its connection exclusively).
 *
 * Runtime props: -Dit.enabled=true -Dit.target=localhost:29091 -Dit.tls=true -Dit.ca=...
 */
class F03NegativeIntegrationTest {

    @BeforeAll
    static void setup() {
        Assumptions.assumeTrue(Boolean.parseBoolean(System.getProperty("it.enabled", "false")),
                "integration disabled");
    }

    private TupagoPaymentClient badClient(String token) {
        String target = System.getProperty("it.target", "localhost:29091");
        String ca = System.getProperty("it.ca", "/tmp/tupago3-cert-grpc-ca.pem");
        TlsConfig tlsConfig = ca.isBlank() ? TlsConfig.systemTrust() : TlsConfig.trustCertChainFile(ca);
        return TupagoPaymentClient.builder()
                .target(target)
                .credentials((CredentialProvider) () -> token)
                .tls(tlsConfig)
                .build();
    }

    @Test
    void invalidBearerIsUnauthenticated() {
        TupagoPaymentClient bad = badClient("invalid.invalid.invalid");
        assertThatThrownBy(() -> bad.getCatalogo("TST99"))
                .isInstanceOf(TupagoUnauthenticatedException.class);
    }

    @Test
    void missingBearerIsUnauthenticated() {
        // The SDK's BearerInterceptor fails fast locally (before any network I/O) when no token
        // is supplied, surfacing UNAUTHENTICATED.
        TupagoPaymentClient anon = TupagoPaymentClient.builder()
                .target(System.getProperty("it.target", "localhost:29091"))
                .credentials((CredentialProvider) () -> null)
                .tls(null)
                .build();
        assertThatThrownBy(() -> anon.getCatalogo("TST99"))
                .isInstanceOf(TupagoUnauthenticatedException.class);
    }
}
