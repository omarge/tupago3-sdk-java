package com.tupago.sdk.client;

import com.tupago.sdk.auth.CredentialProvider;
import com.tupago.sdk.error.TupagoNotFoundException;
import com.tupago.sdk.error.TupagoUnauthenticatedException;
import com.tupago.sdk.grpc.v1.CatalogoInstitucion;
import com.tupago.sdk.grpc.v1.ClientePerfil;
import com.tupago.sdk.security.TlsConfig;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * REAL F03-R3 positive matrix for a single operator-issued, scoped JWT (token A or B) against the
 * isolated cert/loopback backend3. Gated on -Dit.enabled=true so the unit suite is unaffected.
 *
 * Runtime props (never committed):
 *   -Dit.enabled=true
 *   -Dit.target=localhost:29091
 *   -Dit.tls=true
 *   -Dit.ca=/tmp/tupago3-cert-grpc-ca.pem
 *   -Dit.jwt=<scoped operator JWT for A or B>
 *   -Dit.ownerId=910001 (A) / 910002 (B)
 *   -Dit.foreignId=910002 (A) / 910001 (B)
 *
 * Only SAME-TOKEN reads are performed here. The backend resolves the security context per gRPC call
 * from the bearer token (verified by F03R4DiagnosticTest I01-I10, including per-call credential
 * switching on a single connection). The negative (invalid/missing token) cases live in
 * F03NegativeIntegrationTest.
 */
class F03MatrixIntegrationTest {

    private static TupagoPaymentClient client;
    private static long ownerId;
    private static long foreignId;

    @BeforeAll
    static void setup() {
        Assumptions.assumeTrue(Boolean.parseBoolean(System.getProperty("it.enabled", "false")),
                "integration disabled");
        String target = System.getProperty("it.target", "localhost:29091");
        String jwt = readToken(System.getProperty("it.jwtFile"), System.getProperty("it.jwt", ""));
        Assumptions.assumeTrue(!jwt.isBlank(), "it.jwt required for integration");
        boolean tls = Boolean.parseBoolean(System.getProperty("it.tls", "true"));
        TlsConfig tlsConfig = null;
        if (tls) {
            String ca = System.getProperty("it.ca", "/tmp/tupago3-cert-grpc-ca.pem");
            tlsConfig = ca.isBlank() ? TlsConfig.systemTrust() : TlsConfig.trustCertChainFile(ca);
        }
        ownerId = Long.parseLong(System.getProperty("it.ownerId", "910001"));
        foreignId = Long.parseLong(System.getProperty("it.foreignId", "910002"));

        client = TupagoPaymentClient.builder()
                .target(target)
                .credentials((CredentialProvider) () -> jwt)
                .tls(tlsConfig)
                .build();
    }

    private static String readToken(String file, String fallback) {
        if (file != null && !file.isBlank()) {
            try {
                return new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(file)),
                        java.nio.charset.StandardCharsets.UTF_8).trim();
            } catch (Exception ignore) {
                // fall through
            }
        }
        return fallback == null ? "" : fallback;
    }

    @Test
    void ownerClienteIsReadable() {
        ClientePerfil p = client.getCliente(ownerId);
        assertThat(p.getCliente().getId()).isEqualTo(ownerId);
    }

    @Test
    void foreignClienteIsNotFound() {
        assertThatThrownBy(() -> client.getCliente(foreignId))
                .isInstanceOf(TupagoNotFoundException.class);
    }

    @Test
    void catalogoIsReadable() {
        CatalogoInstitucion c = client.getCatalogo("TST99");
        assertThat(c.getClaveInstitucion()).isEqualTo("TST99");
    }
}
