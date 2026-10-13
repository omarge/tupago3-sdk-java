package com.tupago.sdk.grpc;

import com.tupago.sdk.core.auth.CredentialProvider;
import com.tupago.sdk.core.banxico.BanxicoMessage;
import com.tupago.sdk.core.client.PaymentOperations;
import com.tupago.sdk.core.config.TimeoutConfig;
import com.tupago.sdk.core.idempotency.IdempotencyKey;
import com.tupago.sdk.core.model.CreateQrRequest;
import com.tupago.sdk.core.result.PaymentOperationResult;
import java.math.BigDecimal;
import java.util.function.Function;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Documentation compile-gate (Final-Cert-R1, P19): proves the modern gRPC usage
 * documented in README §7 compiles against the real public API. It mirrors the
 * README snippet and captures the exact {@code createQr(CreateQrRequest)} call
 * shape via a method reference so the signature is resolved at compile time
 * WITHOUT performing any network/transport operation.
 */
final class ModernApiSnippetCompileTest {

    @Test
    void modernGrpcApiSnippetCompilesAndIsWellFormed() {
        // Mirrors README §7 exactly: no embedded secret, credentials resolved from env.
        CredentialProvider credentials = () -> System.getenv("TUPAGO_JWT");   // Example only.
        TimeoutConfig timeouts = TimeoutConfig.defaults()
                .withRequestMillis(10_000L)
                .withCreateOperationMillis(10_000L);
        GrpcClientConfig config = GrpcClientConfig.builder()
                .target("127.0.0.1:9090")
                .credentials(credentials)
                .timeouts(timeouts)
                .build();

        try (GrpcTupagoClient client = GrpcTupagoClient.create(config)) {
            CreateQrRequest request = CreateQrRequest.of(
                    1L,
                    new BigDecimal("150.00"),
                    "Pago de servicio",
                    123456,
                    "Juan Perez",
                    "juan@example.com",
                    1,
                    24,
                    IdempotencyKey.of("order-0001"));

            // Compile-only proof of the documented call shape (no network call):
            // the method reference resolves the exact return/parameter types.
            PaymentOperations ops = client.payments();
            Function<CreateQrRequest, PaymentOperationResult<BanxicoMessage>> call = ops::createQr;
            Assertions.assertNotNull(call);
        }
    }
}
