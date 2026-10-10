package com.tupago.sdk.client;

import com.tupago.sdk.auth.BearerInterceptor;
import com.tupago.sdk.auth.CredentialProvider;
import com.tupago.sdk.error.StatusMapper;
import com.tupago.sdk.error.TupagoException;
import com.tupago.sdk.error.TupagoUnknownOutcomeException;
import com.tupago.sdk.grpc.v1.CatalogoInstitucion;
import com.tupago.sdk.grpc.v1.ClientePerfil;
import com.tupago.sdk.grpc.v1.CreateQrRequest;
import com.tupago.sdk.grpc.v1.CreateTelefonicoRequest;
import com.tupago.sdk.grpc.v1.GetCatalogoRequest;
import com.tupago.sdk.grpc.v1.GetClienteRequest;
import com.tupago.sdk.grpc.v1.GetStatusRequest;
import com.tupago.sdk.grpc.v1.JsonResponse;
import com.tupago.sdk.grpc.v1.PaymentServiceGrpc;
import com.tupago.sdk.grpc.v1.PaymentServiceGrpc.PaymentServiceBlockingStub;
import com.tupago.sdk.retry.NoRetryPolicy;
import com.tupago.sdk.retry.RetryPolicy;
import com.tupago.sdk.security.ChannelSecurity;
import com.tupago.sdk.security.TlsConfig;
import io.grpc.Channel;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.StatusRuntimeException;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Blocking gRPC client for the TUPAGO3 {@code PaymentService}. The public surface is 1:1 with the
 * frozen backend contract (SDK-JAVA-00 section 3). Create operations use {@link NoRetryPolicy}
 * (gate G4); read operations use the configured {@link RetryPolicy} (transient-only).
 */
public final class TupagoPaymentClient implements AutoCloseable {

    private final Channel channel;
    private final PaymentServiceBlockingStub baseStub;
    private final long deadlineMillis;
    private final RetryPolicy readRetryPolicy;

    TupagoPaymentClient(Channel channel, long deadlineMillis, RetryPolicy readRetryPolicy) {
        this.channel = channel;
        this.baseStub = PaymentServiceGrpc.newBlockingStub(channel);
        this.deadlineMillis = deadlineMillis;
        this.readRetryPolicy = readRetryPolicy;
    }

    public ClientePerfil getCliente(long id) {
        return execute("GetCliente",
                () -> stub().getCliente(GetClienteRequest.newBuilder().setId(id).build()),
                readRetryPolicy);
    }

    public CatalogoInstitucion getCatalogo(String claveInstitucion) {
        return execute("GetCatalogo",
                () -> stub().getCatalogo(GetCatalogoRequest.newBuilder()
                        .setClaveInstitucion(claveInstitucion == null ? "" : claveInstitucion).build()),
                readRetryPolicy);
    }

    public JsonResponse getQrStatus(String idc, long idCuenta) {
        return execute("GetQrStatus",
                () -> stub().getQrStatus(GetStatusRequest.newBuilder()
                        .setIdc(idc == null ? "" : idc).setIdCuenta(idCuenta).build()),
                readRetryPolicy);
    }

    public JsonResponse getTelefonicoStatus(String idc, long idCuenta) {
        return execute("GetTelefonicoStatus",
                () -> stub().getTelefonicoStatus(GetStatusRequest.newBuilder()
                        .setIdc(idc == null ? "" : idc).setIdCuenta(idCuenta).build()),
                readRetryPolicy);
    }

    /**
     * Creates a QR payment. NEVER auto-retried (gate G4). Idempotency: pass a stable
     * {@code idempotency_key}; the server dedupes identical keys. On a transient transport error
     * the outcome is UNKNOWN (see {@link TupagoUnknownOutcomeException}) — do NOT blind-resubmit;
     * reuse the same key or call {@link #getQrStatus}. On {@code ALREADY_EXISTS} /
     * {@code ABORTED/TUPAGO_PENDING_RECONCILIATION}, query status with the same idempotency key.
     */
    public JsonResponse createQr(CreateQrRequest request) {
        Objects.requireNonNull(request, "request");
        return executeFinancial("CreateQr", () -> stub().createQr(request));
    }

    /**
     * Creates a telefonic payment. NEVER auto-retried (gate G4). Same idempotency/unknown-outcome
     * policy as {@link #createQr}.
     */
    public JsonResponse createTelefonico(CreateTelefonicoRequest request) {
        Objects.requireNonNull(request, "request");
        return executeFinancial("CreateTelefonico", () -> stub().createTelefonico(request));
    }

    private PaymentServiceBlockingStub stub() {
        return baseStub.withDeadlineAfter(deadlineMillis, TimeUnit.MILLISECONDS);
    }

    private <T> T execute(String op, Supplier<T> call, RetryPolicy policy) {
        int attempt = 0;
        while (true) {
            try {
                return call.get();
            } catch (StatusRuntimeException sre) {
                TupagoException te = StatusMapper.map(sre);
                if (policy.shouldRetry(attempt, te)) {
                    attempt++;
                    try {
                        Thread.sleep(backoffMillis(attempt));
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw te;
                    }
                    continue;
                }
                throw te;
            }
        }
    }

    private long backoffMillis(int attempt) {
        // Conservative fixed 250ms; BackoffRetryPolicy holds the real policy if needed.
        return 250L * attempt;
    }

    /**
     * Execute a CREATE operation. NoRetryPolicy is enforced (gate G4). Crucially, a transient
     * transport error on a financial create is reclassified as an UNKNOWN OUTCOME
     * (non-retryable) so the caller can never blind-resubmit (correction #3).
     */
    private <T> T executeFinancial(String op, Supplier<T> call) {
        try {
            return call.get();
        } catch (StatusRuntimeException sre) {
            TupagoException te = StatusMapper.map(sre);
            if (te.isRetryable()) {
                throw new TupagoUnknownOutcomeException(sre.getStatus());
            }
            throw te;
        }
    }

    @Override
    public void close() {
        if (channel instanceof ManagedChannel mc && !mc.isShutdown()) {
            mc.shutdownNow();
        }
    }

    // ---- builder ----

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String target;
        private CredentialProvider credentials;
        private TlsConfig tls;
        private long deadlineMillis = 10_000L;
        private RetryPolicy readRetryPolicy = com.tupago.sdk.retry.BackoffRetryPolicy.of(3);

        public Builder target(String target) {
            this.target = target;
            return this;
        }

        public Builder credentials(CredentialProvider credentials) {
            this.credentials = credentials;
            return this;
        }

        public Builder tls(TlsConfig tls) {
            this.tls = tls;
            return this;
        }

        public Builder deadlineMillis(long deadlineMillis) {
            if (deadlineMillis <= 0) {
                throw new IllegalArgumentException("deadlineMillis must be positive");
            }
            this.deadlineMillis = deadlineMillis;
            return this;
        }

        public Builder readRetryPolicy(RetryPolicy readRetryPolicy) {
            this.readRetryPolicy = readRetryPolicy;
            return this;
        }

        public TupagoPaymentClient build() {
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(credentials, "credentials");
            ManagedChannelBuilder<?> mcb;
            if (ChannelSecurity.isLoopback(extractHost(target))) {
                mcb = ChannelSecurity.plaintextLoopback(target);
            } else if (tls != null) {
                mcb = ChannelSecurity.tls(target, tls);
            } else {
                // Non-loopback without TLS: fail closed.
                throw new IllegalStateException(
                        "TLS required for non-loopback target '" + target + "' (gate G8). Provide tls(TlsConfig).");
            }
            Channel channel = mcb.build();
            Channel intercepted = io.grpc.ClientInterceptors.intercept(
                    channel, new BearerInterceptor(credentials));
            return new TupagoPaymentClient(intercepted, deadlineMillis, readRetryPolicy);
        }

        private static String extractHost(String target) {
            String t = target;
            int i = t.indexOf("://");
            if (i >= 0) t = t.substring(i + 3);
            if (t.startsWith("/")) t = t.substring(1);
            if (t.startsWith("[")) {
                int e = t.indexOf(']');
                return e > 0 ? t.substring(1, e) : t;
            }
            int c = t.indexOf(':');
            return c > 0 ? t.substring(0, c) : t;
        }
    }
}
