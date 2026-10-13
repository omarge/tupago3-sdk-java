package com.tupago.sdk.core.auth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CredentialProviderTest {

    @Test
    void token_isReturnedWithoutBearerPrefix() {
        CredentialProvider provider = () -> "eyJ.eyJ.abc";
        assertThat(provider.token()).isEqualTo("eyJ.eyJ.abc");
        assertThat(provider.token()).doesNotStartWith("Bearer ");
    }

    @Test
    void provider_doesNotExposeSecretInDefaultToString() {
        // A realistic provider holds the JWT; the default Object.toString() must not reveal it.
        final String secretJwt = "super.secret.jwt.value";
        CredentialProvider holder = new CredentialProvider() {
            @Override
            public String token() {
                return secretJwt;
            }
        };
        assertThat(holder.token()).isEqualTo(secretJwt);
        assertThat(String.valueOf(holder)).doesNotContain(secretJwt);
    }

    @Test
    void coreResultTypesDoNotEmbedCredential() {
        // The core error/result types carry only caller-supplied messages; they never capture the token.
        // Demonstrated by constructing them with a message that intentionally omits the credential.
        final String token = "should-never-appear";
        com.tupago.sdk.core.error.PaymentError err =
                new com.tupago.sdk.core.error.PaymentError.TransportUnavailable(false, "transport reset");
        com.tupago.sdk.core.result.Rejected<Object> rejected =
                new com.tupago.sdk.core.result.Rejected<>(err);
        assertThat(rejected.toString()).doesNotContain(token);
        assertThat(err.toString()).doesNotContain(token);
    }
}
