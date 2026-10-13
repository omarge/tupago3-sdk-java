package com.tupago.sdk.grpc;

import com.tupago.sdk.core.auth.CredentialProvider;
import com.tupago.sdk.core.config.TimeoutConfig;
import com.tupago.sdk.core.retry.NoRetryPolicy;
import com.tupago.sdk.core.retry.RetryPolicy;
import com.tupago.sdk.security.TlsConfig;
import java.util.Objects;

/**
 * Immutable configuration for the TUPAGO3 gRPC transport adapter (SDK-04).
 *
 * <p>The gRPC adapter depends on the transport-neutral core and on the historical channel/security
 * plumbing ( {@code com.tupago.sdk.security.ChannelSecurity} / {@code TlsConfig},
 * {@code com.tupago.sdk.auth.BearerInterceptor}). It is built through {@link #builder()}; all fields
 * are validated and no secret is retained in logs.</p>
 *
 * <p>Contract notes:
 * <ul>
 *   <li>{@code target} is the gRPC target authority (e.g. {@code 127.0.0.1:9090} or
 *       {@code dns:///api.tupago.example:443}). Plaintext is permitted ONLY for loopback targets;
 *       non-loopback targets REQUIRE a {@link TlsConfig} (fail-closed, gate G8).</li>
 *   <li>The {@link CredentialProvider} supplies the raw Bearer JWT per RPC; the adapter adds exactly
 *       {@code authorization: Bearer <token>} (lowercase ASCII metadata key) and never logs it.</li>
 *   <li>{@link TimeoutConfig} drives connect / request / read / create-operation deadlines, mapped as
 *       described in {@code GrpcTransport}.</li>
 *   <li>{@link RetryPolicy} controls only the attempt bound and backoff; {@code RetryClassifier} is the
 *       sole semantic retry authority (SDK-04).</li>
 * </ul>
 */
public final class GrpcClientConfig {

    private final String target;
    private final CredentialProvider credentials;
    private final TlsConfig tls;
    private final TimeoutConfig timeouts;
    private final RetryPolicy retryPolicy;

    private GrpcClientConfig(Builder b) {
        this.target = b.target;
        this.credentials = b.credentials;
        this.tls = b.tls;
        this.timeouts = b.timeouts;
        this.retryPolicy = b.retryPolicy;
    }

    public String target() {
        return target;
    }

    public CredentialProvider credentials() {
        return credentials;
    }

    public TlsConfig tls() {
        return tls;
    }

    public TimeoutConfig timeouts() {
        return timeouts;
    }

    public RetryPolicy retryPolicy() {
        return retryPolicy;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Mutable builder for {@link GrpcClientConfig}. */
    public static final class Builder {
        private String target;
        private CredentialProvider credentials;
        private TlsConfig tls;
        private TimeoutConfig timeouts = TimeoutConfig.defaults();
        private RetryPolicy retryPolicy = NoRetryPolicy.INSTANCE;

        public Builder target(String target) {
            this.target = Objects.requireNonNull(target, "target must not be null");
            return this;
        }

        public Builder credentials(CredentialProvider credentials) {
            this.credentials = Objects.requireNonNull(credentials, "credentials must not be null");
            return this;
        }

        public Builder tls(TlsConfig tls) {
            this.tls = tls;
            return this;
        }

        public Builder timeouts(TimeoutConfig timeouts) {
            this.timeouts = Objects.requireNonNull(timeouts, "timeouts must not be null");
            return this;
        }

        public Builder retryPolicy(RetryPolicy retryPolicy) {
            this.retryPolicy = Objects.requireNonNull(retryPolicy, "retryPolicy must not be null");
            return this;
        }

        public GrpcClientConfig build() {
            if (target == null || target.isBlank()) {
                throw new IllegalStateException("target is required");
            }
            if (credentials == null) {
                throw new IllegalStateException("credentials is required");
            }
            return new GrpcClientConfig(this);
        }
    }
}
