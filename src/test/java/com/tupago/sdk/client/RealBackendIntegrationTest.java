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
        Assumptions.assumeTrue(!jwt.isBlank(), "it.jwt required for integration");
        client = TupagoPaymentClient.builder()
                .target(target)
                .credentials((CredentialProvider) () -> jwt)
                .build();
        anonClient = TupagoPaymentClient.builder()
                .target(target)
                .credentials((CredentialProvider) () -> null)
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
    void missingBearerIsUnauthenticated() {
        assertThatThrownBy(() -> anonClient.getCatalogo("301"))
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
