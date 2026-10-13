package com.tupago.sdk.auth;

/**
 * Historical gRPC CredentialProvider. Reconciled with the SDK-01 transport-neutral core
 * (SDK-04): this interface is now a compatibility alias of
 * {@link com.tupago.sdk.core.auth.CredentialProvider} (same {@code String token()} contract), so
 * any core provider is also a valid legacy provider. New code should depend on the core type directly.
 *
 * <p>Supplies the current Bearer JWT for every RPC. Implementations obtain the token from an
 * external source (OAuth2 client-credentials flow, secret manager, etc.). The SDK NEVER
 * embeds credentials (gate G3); implementations must likewise avoid hard-coded secrets.</p>
 *
 * @deprecated use {@link com.tupago.sdk.core.auth.CredentialProvider} from {@code tupago-sdk-core}.
 */
@Deprecated(since = "SDK-04", forRemoval = true)
@FunctionalInterface
public interface CredentialProvider extends com.tupago.sdk.core.auth.CredentialProvider {

    /**
     * @return a valid {@code Bearer} JWT (without the "Bearer " prefix), or {@code null} if
     *         unavailable (the client will then fail with UNAUTHENTICATED).
     */
    @Override
    String token();
}
