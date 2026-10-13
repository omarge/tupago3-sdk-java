package com.tupago.sdk.rest;

import com.tupago.sdk.core.config.TimeoutConfig;
import com.tupago.sdk.core.model.GetClienteRequest;
import com.tupago.sdk.core.result.PaymentOperationResult;
import com.tupago.sdk.core.result.TransportFailure;
import com.tupago.sdk.core.error.PaymentError;
import com.tupago.sdk.core.retry.RetryPolicy;
import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class RestRetryTest {

    private static final class FixedRetryPolicy implements RetryPolicy {
        private final int maxAttempts;
        private final Duration baseDelay;

        FixedRetryPolicy(int maxAttempts, Duration baseDelay) {
            this.maxAttempts = maxAttempts;
            this.baseDelay = baseDelay;
        }

        @Override
        public int maxAttempts() {
            return maxAttempts;
        }

        @Override
        public Duration baseDelay() {
            return baseDelay;
        }
    }

    @Test
    void readPreExecutionFailureIsClassifiedRetryableButExhaustsAttempts() {
        // Point at a closed port: every attempt is a ConnectException (proven pre-execution).
        RestClientConfig cfg = RestClientConfig.builder()
                .baseUri("http://127.0.0.1:1")
                .credentials(() -> "tok")
                .timeouts(TimeoutConfig.defaults())
                .retryPolicy(new FixedRetryPolicy(3, Duration.ofMillis(200)))
                .build();
        RestTupagoClient client = RestTupagoClient.create(cfg);

        long start = System.nanoTime();
        PaymentOperationResult<?> r = client.clientes().getCliente(GetClienteRequest.of(1L));
        long elapsedMs = Duration.ofNanos(System.nanoTime() - start).toMillis();

        assertThat(r).isInstanceOf(TransportFailure.class);
        TransportFailure<?> tf = (TransportFailure<?>) r;
        assertThat(tf.error()).isInstanceOf(PaymentError.TransportUnavailable.class);
        // Proven pre-execution failure -> mayHaveBeenSent must be false (read would be retryable).
        assertThat(tf.mayHaveBeenSent()).isFalse();
        // Two backoffs of 200ms => at least ~400ms elapsed, proving the read loop actually retried.
        assertThat(elapsedMs).isGreaterThanOrEqualTo(300L);
        client.close();
    }

    @Test
    void readServerErrorDoesNotRetryEvenWithPolicy() throws IOException {
        TestBackend backend = new TestBackend();
        backend.setResponse(TestBackend.ScriptedResponse.json(503, "{\"detail\":\"down\"}"));
        try {
            RestClientConfig cfg = RestClientConfig.builder()
                    .baseUri(backend.baseUri())
                    .credentials(() -> "tok")
                    .retryPolicy(new FixedRetryPolicy(5, Duration.ofMillis(50)))
                    .build();
            RestTupagoClient client = RestTupagoClient.create(cfg);
            PaymentOperationResult<?> r = client.clientes().getCliente(GetClienteRequest.of(1L));
            client.close();
            assertThat(r).isInstanceOf(TransportFailure.class);
            // 5xx reached the server -> mayHaveBeenSent true -> never retry, reconcile instead.
            assertThat(((TransportFailure<?>) r).mayHaveBeenSent()).isTrue();
            assertThat(backend.requestCount()).isEqualTo(1);
        } finally {
            backend.stop();
        }
    }

    @Test
    void createNeverRetriesEvenWithPolicy() throws IOException {
        TestBackend backend = new TestBackend();
        backend.setResponse(TestBackend.ScriptedResponse.jsonDelayed(200,
                "{\"mensaje\":\"{}\",\"emisor\":\"E\",\"banco\":\"B\"}", 1500));
        try {
            TimeoutConfig tc = new TimeoutConfig(5_000L, 30_000L, 30_000L, 300L);
            RestClientConfig cfg = RestClientConfig.builder()
                    .baseUri(backend.baseUri())
                    .credentials(() -> "tok")
                    .timeouts(tc)
                    .retryPolicy(new FixedRetryPolicy(5, Duration.ofMillis(50)))
                    .build();
            RestTupagoClient client = RestTupagoClient.create(cfg);
            PaymentOperationResult<?> r = client.payments().createQr(
                    com.tupago.sdk.core.model.CreateQrRequest.of(1L,
                            new java.math.BigDecimal("1"), "c", 1, "n", "e", 1, 5,
                            com.tupago.sdk.core.idempotency.IdempotencyKey.of("k")));
            client.close();
            assertThat(r).isInstanceOf(TransportFailure.class);
            assertThat(backend.requestCount()).isEqualTo(1);
        } finally {
            backend.stop();
        }
    }
}
