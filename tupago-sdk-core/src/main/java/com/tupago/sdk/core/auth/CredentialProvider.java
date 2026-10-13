package com.tupago.sdk.core.auth;

/**
 * Supplies the current Bearer JWT for every SDK call (SDK-01 auth contract, section 7 / G5).
 *
 * <p>Contract obligations for all implementations:
 * <ul>
 *   <li>The SDK NEVER signs JWTs and NEVER knows the HS512 signing key.</li>
 *   <li>The SDK NEVER persists secrets and NEVER prints or logs the token.</li>
 *   <li>The token returned is the raw JWT <em>without</em> the {@code "Bearer "} prefix; the casing
 *       adaptation (REST/GraphQL {@code Authorization} header vs gRPC lowercase {@code authorization}
 *       metadata) is the responsibility of each transport adapter, not this abstraction.</li>
 * </ul>
 *
 * <p>The core models only the token value. Implementations obtain it from an external source
 * (OAuth2 client-credentials, a secret manager, operator-issued short-lived files, ...).</p>
 */
@FunctionalInterface
public interface CredentialProvider {

    /**
     * @return a valid Bearer JWT (without the {@code "Bearer "} prefix), or {@code null} if unavailable
     *         (the transport will then fail with an authentication error).
     */
    String token();
}
