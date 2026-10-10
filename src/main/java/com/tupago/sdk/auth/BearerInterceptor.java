package com.tupago.sdk.auth;

import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ForwardingClientCall;
import io.grpc.ForwardingClientCallListener;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Status;
import java.util.Objects;

/**
 * Attaches the {@code authorization: Bearer <jwt>} metadata header on every RPC, mirroring the
 * backend's {@code JwtServerInterceptor} expectation (lowercase ASCII key). The token is read
 * at call time so rotation/refresh is transparent and the token is never cached on the channel.
 */
public final class BearerInterceptor implements ClientInterceptor {

    /** Metadata key expected by the backend (ASCII, lowercase), per payment.proto gateway. */
    public static final Metadata.Key<String> AUTHORIZATION =
            Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);

    private final CredentialProvider credentialProvider;

    public BearerInterceptor(CredentialProvider credentialProvider) {
        this.credentialProvider = Objects.requireNonNull(credentialProvider, "credentialProvider");
    }

    @Override
    public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
            MethodDescriptor<ReqT, RespT> methodDescriptor, final CallOptions callOptions, Channel channel) {
        ClientCall<ReqT, RespT> call = channel.newCall(methodDescriptor, callOptions);
        return new ForwardingClientCall.SimpleForwardingClientCall<ReqT, RespT>(call) {
            @Override
            public void start(Listener<RespT> responseListener, Metadata headers) {
                String token = credentialProvider.token();
                if (token == null || token.isBlank()) {
                    // Fail fast locally; backend would also reject with UNAUTHENTICATED.
                    throw Status.UNAUTHENTICATED
                            .withDescription("No bearer token supplied by CredentialProvider")
                            .asRuntimeException();
                }
                headers.discardAll(AUTHORIZATION);
                headers.put(AUTHORIZATION, "Bearer " + token);
                super.start(new ForwardingClientCallListener.SimpleForwardingClientCallListener<RespT>(responseListener) {
                }, headers);
            }
        };
    }
}
