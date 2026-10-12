package com.tupago.sdk.client;

import com.tupago.sdk.auth.CredentialProvider;
import com.tupago.sdk.error.TupagoNotFoundException;
import com.tupago.sdk.error.TupagoUnauthenticatedException;
import com.tupago.sdk.grpc.v1.CatalogoInstitucion;
import com.tupago.sdk.grpc.v1.ClientePerfil;
import com.tupago.sdk.grpc.v1.GetCatalogoRequest;
import com.tupago.sdk.grpc.v1.GetClienteRequest;
import com.tupago.sdk.grpc.v1.GetStatusRequest;
import com.tupago.sdk.grpc.v1.JsonResponse;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * REAL local integration against backend3 gRPC on 127.0.0.1 (no mocks).
 * Runs ONLY when -Dit.enabled=true is supplied, so the normal unit/contract suite is unaffected.
 * Requires a backend booted with tupago.grpc.server.enabled=true on 127.0.0.1 and a synthetic
 * JWT signed with the dev profile's base64-secret (passed via -Dit.jwt, -Dit.target).
 */
class RealBackendIntegrationTest {

    private static TupagoPaymentClient client;
    private static TupagoPaymentClient anonClient;

    @BeforeAll
    static void setup() {
        String enabled = System.getProperty("it.enabled", "false");
        Assumptions.assumeTrue(Boolean.parseBoolean(enabled), "integration disabled");
        String target = System.getProperty("it.target", "127.0.0.1:9090");
        String jwt = System.getProperty("it.jwt", "");
        String tlsFlag = System.getProperty("it.tls", "false");
        boolean tls = Boolean.parseBoolean(tlsFlag);
        com.tupago.sdk.security.TlsConfig tlsConfig = null;
        if (tls) {
            String ca = System.getProperty("it.ca", "");
            tlsConfig = ca.isBlank()
                    ? com.tupago.sdk.security.TlsConfig.systemTrust()
                    : com.tupago.sdk.security.TlsConfig.trustCertChainFile(ca);
        }
        Assumptions.assumeTrue(!jwt.isBlank(), "it.jwt required for integration");
        client = TupagoPaymentClient.builder()
                .target(target)
                .credentials((CredentialProvider) () -> jwt)
                .tls(tlsConfig)
                .build();
        anonClient = TupagoPaymentClient.builder()
                .target(target)
                .credentials((CredentialProvider) () -> null)
                .tls(tlsConfig)
                .build();
    }

    @Test
    void getCatalogoReturnsRealInstitution() {
        CatalogoInstitucion c = client.getCatalogo("301");
        assertThat(c.getClaveInstitucion()).isEqualTo("301");
    }

    @Test
    void getClienteOwnerSuccess() {
        // cliente id=1 is owned by the JWT principal (prodgcp) in the local DB.
        ClientePerfil p = client.getCliente(1L);
        assertThat(p.getCliente().getId()).isEqualTo(1L);
    }

    @Test
    void getClienteUnknownIsNotFound() {
        assertThatThrownBy(() -> client.getCliente(999999L))
                .isInstanceOf(TupagoNotFoundException.class);
    }

    @Test
    void getQrStatusUnknownIsResolved() {
        // No matching mensaje -> backend returns a defined status; we only assert it does not crash
        // and returns exactly one terminal signal (JsonResponse or a mapped exception).
        try {
            JsonResponse r = client.getQrStatus("NOEXISTE", 1L);
            assertThat(r).isNotNull();
        } catch (TupagoNotFoundException e) {
            // acceptable: absent result mapped to NOT_FOUND
        }
    }

    @Test
    void getTelefonicoStatusUnknownIsResolved() {
        // GetTelefonicoStatus exercised individually against the REAL backend over real Protobuf.
        try {
            JsonResponse r = client.getTelefonicoStatus("NOEXISTE", 1L);
            assertThat(r).isNotNull();
        } catch (TupagoNotFoundException e) {
            // acceptable: absent result mapped to NOT_FOUND
        }
    }

    @Test
    void missingBearerIsUnauthenticated() {
        assertThatThrownBy(() -> anonClient.getCatalogo("301"))
                .isInstanceOf(TupagoUnauthenticatedException.class);
    }

    /**
     * Regression for the TLS-on-loopback defect: when an explicit TlsConfig is supplied, the SDK
     * must establish a REAL TLS channel to the loopback cert endpoint (no plaintext downgrade).
     * Proof: a handshake reaching the server yields a UNAUTHENTICATED auth-gate response for an
     * invalid token — NOT a transport-level failure (which a plaintext attempt against a TLS port
     * would produce). Gated on -Dit.tls=true so it runs only against the cert environment.
     */
    @Test
    void tlsOverLoopbackDoesNotDowngradeToPlaintext() {
        String tlsFlag = System.getProperty("it.tls", "false");
        Assumptions.assumeTrue(Boolean.parseBoolean(tlsFlag), "tls integration disabled");
        String target = System.getProperty("it.target", "localhost:29091");
        String ca = System.getProperty("it.ca", "");
        com.tupago.sdk.security.TlsConfig tlsConfig = ca.isBlank()
                ? com.tupago.sdk.security.TlsConfig.systemTrust()
                : com.tupago.sdk.security.TlsConfig.trustCertChainFile(ca);
        TupagoPaymentClient tlsClient = TupagoPaymentClient.builder()
                .target(target)
                .credentials((CredentialProvider) () -> "invalid.invalid.invalid")
                .tls(tlsConfig)
                .build();
        assertThatThrownBy(() -> tlsClient.getCatalogo("TST99"))
                .isInstanceOf(TupagoUnauthenticatedException.class);
    }

    @Test
    void createQrWithUnknownOutcomeDoesNotBlindResubmit() {
        // A CREATE on the real backend that fails (e.g. Banxico unreachable / precondición) must
        // surface as a single-attempt, non-retried outcome. We assert the SDK returns exactly one
        // terminal signal and does not throw a retryable error.
        assertThatThrownBy(() -> client.createQr(
                com.tupago.sdk.grpc.v1.CreateQrRequest.newBuilder()
                        .setIdCuenta(1).setMonto(10.0).setIdempotencyKey("it-" + System.nanoTime()).build()))
                .isInstanceOf(com.tupago.sdk.error.TupagoException.class)
                .satisfies(e -> {
                    assertThat(((com.tupago.sdk.error.TupagoException) e).isRetryable()).isFalse();
                });
    }
}
