package com.tupago.sdk.grpc.internal;

import com.tupago.sdk.auth.BearerInterceptor;
import com.tupago.sdk.core.auth.CredentialProvider;
import com.tupago.sdk.core.config.TimeoutConfig;
import com.tupago.sdk.core.error.PaymentError;
import com.tupago.sdk.core.result.PaymentOperationResult;
import com.tupago.sdk.core.result.Rejected;
import com.tupago.sdk.core.result.TransportFailure;
import com.tupago.sdk.core.retry.OperationKind;
import com.tupago.sdk.core.retry.RetryClassifier;
import com.tupago.sdk.core.retry.RetryDecision;
import com.tupago.sdk.core.retry.RetryPolicy;
import com.tupago.sdk.grpc.GrpcClientConfig;
import com.tupago.sdk.grpc.v1.PaymentServiceGrpc;
import com.tupago.sdk.grpc.v1.PaymentServiceGrpc.PaymentServiceBlockingStub;
import com.tupago.sdk.security.ChannelSecurity;
import io.grpc.Channel;
import io.grpc.ClientInterceptors;
import io.grpc.ManagedChannel;
import io.grpc.StatusRuntimeException;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * Low-level gRPC execution engine (SDK-04).
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>Build a fail-closed channel via {@link ChannelSecurity#forTarget(String, com.tupago.sdk.security.TlsConfig)}
 *       (plaintext loopback-only; TLS required off-host).</li>
 *   <li>Attach exactly {@code authorization: Bearer <token>} per RPC through {@link BearerInterceptor}, which
 *       reads the token at call time (rotation-safe, never cached on the channel) and fails fast with
 *       UNAUTHENTICATED when no token is available (ZERO bytes sent).</li>
 *   <li>Apply {@link TimeoutConfig} as a per-call gRPC deadline.</li>
 *   <li>Run the conservative retry loop driven solely by {@link RetryClassifier}: creates NEVER blind-retry;
 *       reads retry only on a proven pre-execution transport failure (which gRPC does not expose here, so in
 *       practice no speculative retry happens).</li>
 * </ul>
 *
 * <p>This class is internal: it references {@code io.grpc} and generated stubs and is never part of the
 * public API. The public surface is {@code com.tupago.sdk.grpc.GrpcTupagoClient}.</p>
 */
public final class GrpcTransport {

    /** Maps a successful unary RPC response ({@code R}) to a core result for a specific operation. */
    public interface ResponseMapper<T, R> {
        PaymentOperationResult<T> apply(R response);
    }

    private final GrpcClientConfig config;
    private final ManagedChannel channel;
    private final PaymentServiceBlockingStub baseStub;
    private final RetryPolicy retryPolicy;
    private final TimeoutConfig timeouts;

    public GrpcTransport(GrpcClientConfig config) {
        this.config = config;
        // Legacy CredentialProvider is a compatibility alias of the core one (SDK-04 reconciliation),
        // so a core provider is wrapped into the legacy shape the interceptor expects.
        com.tupago.sdk.auth.CredentialProvider legacyCreds = () -> config.credentials().token();
        ManagedChannel raw = ChannelSecurity.forTarget(config.target(), config.tls()).build();
        Channel intercepted = ClientInterceptors.intercept(raw, new BearerInterceptor(legacyCreds));
        this.channel = raw;
        this.baseStub = PaymentServiceGrpc.newBlockingStub(intercepted);
        this.retryPolicy = config.retryPolicy();
        this.timeouts = config.timeouts();
    }

    public <T, R> PaymentOperationResult<T> exchange(OperationKind kind, String pendingIdc,
                                                    Function<PaymentServiceBlockingStub, R> rpc,
                                                    ResponseMapper<T, R> onSuccess) {
        int maxAttempts = Math.max(1, retryPolicy.maxAttempts());
        long baseDelay = retryPolicy.baseDelay().toMillis();

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                R resp = rpc.apply(withDeadline(kind));
                return onSuccess.apply(resp);
            } catch (StatusRuntimeException sre) {
                // RetryClassifier is the SINGLE semantic retry authority. The loop only enforces the
                // structural attempt cap; it does NOT decide financial retryability itself.
                PaymentOperationResult<T> result = GrpcErrorTranslator.translate(kind, sre, pendingIdc);
                if (result instanceof TransportFailure<?> tf) {
                    RetryDecision decision = RetryClassifier.classify(kind, tf.error(), tf.mayHaveBeenSent());
                    if (decision == RetryDecision.RETRY && attempt < maxAttempts) {
                        try {
                            Thread.sleep(Math.max(0, baseDelay));
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            return result;
                        }
                        continue;
                    }
                }
                return result;
            } catch (RuntimeException unexpected) {
                // Local result-mapping failure (malformed JSON, shape violation) must fail closed.
                return new Rejected<>(new PaymentError.InternalFailure(
                        "Failed to process gRPC response: " + unexpected.getClass().getSimpleName()));
            }
        }
        // Unreachable: loop always returns.
        return new TransportFailure<>(new PaymentError.TransportUnavailable(true, "unreachable retry loop"), true);
    }

    private PaymentServiceBlockingStub withDeadline(OperationKind kind) {
        long millis;
        if (kind.isCreate()) {
            millis = timeouts.createOperationMillis() > 0 ? timeouts.createOperationMillis() : timeouts.requestMillis();
        } else {
            millis = Math.max(timeouts.requestMillis(), timeouts.readMillis());
        }
        return baseStub.withDeadlineAfter(millis, TimeUnit.MILLISECONDS);
    }

    /** Shut the channel down (best-effort). */
    public void close() {
        if (!channel.isShutdown()) {
            channel.shutdownNow();
        }
    }
}
