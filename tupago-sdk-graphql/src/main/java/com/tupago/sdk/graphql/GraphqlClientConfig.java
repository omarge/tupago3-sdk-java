package com.tupago.sdk.graphql;

import com.tupago.sdk.core.auth.CredentialProvider;
import com.tupago.sdk.core.config.TimeoutConfig;
import com.tupago.sdk.core.retry.NoRetryPolicy;
import com.tupago.sdk.core.retry.RetryPolicy;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.util.Objects;

/**
 * Immutable configuration for the TUPAGO3 GraphQL transport adapter (SDK-03).
 *
 * <p>The GraphQL adapter depends only on the JDK {@link java.net.http.HttpClient} and Jackson. It is
 * built through {@link #builder()}; all fields are validated and no secret is retained in logs.</p>
 *
 * <p>Contract notes:
 * <ul>
 *   <li>{@code baseUri} is the origin (scheme + host + optional port), e.g. {@code https://api.tupago.example}.
 *       The GraphQL {@code /graphql} endpoint path is appended by the adapter (configurable via
 *       {@code graphqlPath}, default {@code /graphql}).</li>
 *   <li>The {@link CredentialProvider} supplies the raw Bearer JWT per request; the adapter adds
 *       exactly {@code Authorization: Bearer <token>} and never logs it.</li>
 *   <li>{@link TimeoutConfig} drives connect / request / read / create-operation deadlines, mapped as
 *       described in {@code GraphqlTransport}.</li>
 * </ul>
 */
public final class GraphqlClientConfig {

    private final URI baseUri;
    private final String graphqlPath;
    private final CredentialProvider credentials;
    private final TimeoutConfig timeouts;
    private final RetryPolicy retryPolicy;
    private final ObjectMapper objectMapper;

    private GraphqlClientConfig(Builder b) {
        this.baseUri = b.baseUri;
        this.graphqlPath = b.graphqlPath;
        this.credentials = b.credentials;
        this.timeouts = b.timeouts;
        this.retryPolicy = b.retryPolicy;
        this.objectMapper = b.objectMapper != null ? b.objectMapper : new ObjectMapper();
    }

    public URI baseUri() {
        return baseUri;
    }

    public String graphqlPath() {
        return graphqlPath;
    }

    public CredentialProvider credentials() {
        return credentials;
    }

    public TimeoutConfig timeouts() {
        return timeouts;
    }

    public RetryPolicy retryPolicy() {
        return retryPolicy;
    }

    public ObjectMapper objectMapper() {
        return objectMapper;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Mutable builder for {@link GraphqlClientConfig}. */
    public static final class Builder {
        private URI baseUri;
        private String graphqlPath = "/graphql";
        private CredentialProvider credentials;
        private TimeoutConfig timeouts = TimeoutConfig.defaults();
        private RetryPolicy retryPolicy = NoRetryPolicy.INSTANCE;
        private ObjectMapper objectMapper;

        public Builder baseUri(String baseUri) {
            this.baseUri = URI.create(Objects.requireNonNull(baseUri, "baseUri must not be null"));
            return this;
        }

        public Builder baseUri(URI baseUri) {
            this.baseUri = Objects.requireNonNull(baseUri, "baseUri must not be null");
            return this;
        }

        public Builder graphqlPath(String graphqlPath) {
            this.graphqlPath = Objects.requireNonNull(graphqlPath, "graphqlPath must not be null");
            return this;
        }

        public Builder credentials(CredentialProvider credentials) {
            this.credentials = Objects.requireNonNull(credentials, "credentials must not be null");
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

        public Builder objectMapper(ObjectMapper objectMapper) {
            this.objectMapper = objectMapper;
            return this;
        }

        public GraphqlClientConfig build() {
            if (baseUri == null) {
                throw new IllegalStateException("baseUri is required");
            }
            validateBaseUri(baseUri);
            if (graphqlPath == null || graphqlPath.isEmpty() || !graphqlPath.startsWith("/")) {
                throw new IllegalArgumentException(
                        "graphqlPath must be a non-empty path starting with '/': " + graphqlPath);
            }
            if (credentials == null) {
                throw new IllegalStateException("credentials is required");
            }
            return new GraphqlClientConfig(this);
        }
    }

    /** Reject non-HTTP origins and URI shapes that could leak into request construction (mirrors REST B14). */
    private static void validateBaseUri(URI uri) {
        if (uri.getScheme() == null
                || (!uri.getScheme().equalsIgnoreCase("http") && !uri.getScheme().equalsIgnoreCase("https"))) {
            throw new IllegalArgumentException(
                    "baseUri scheme must be http or https (got: " + uri.getScheme() + ")");
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw new IllegalArgumentException("baseUri must include a host: " + uri);
        }
        if (uri.getRawQuery() != null) {
            throw new IllegalArgumentException("baseUri must not contain a query: " + uri);
        }
        if (uri.getRawFragment() != null) {
            throw new IllegalArgumentException("baseUri must not contain a fragment: " + uri);
        }
    }
}
