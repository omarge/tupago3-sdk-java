package com.tupago.sdk.core.error;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ErrorTaxonomyTest {

    @Test
    void pendingReconciliation_isNeverRetryableByDefault() {
        PaymentError e = new PaymentError.PendingReconciliation("ambiguous");
        assertThat(e.code()).isEqualTo("PAYMENT_PENDING_RECONCILIATION");
        assertThat(e.category()).isEqualTo(ErrorCategory.PENDING_RECONCILIATION);
        assertThat(e.isRetryableByDefault()).isFalse();
    }

    @Test
    void transportUnavailable_isRetryableByDefaultButConditional() {
        PaymentError e = new PaymentError.TransportUnavailable(false, "reset");
        assertThat(e.code()).isEqualTo("TRANSPORT_UNAVAILABLE");
        assertThat(e.category()).isEqualTo(ErrorCategory.TRANSPORT_UNAVAILABLE);
        assertThat(e.isRetryableByDefault()).isTrue();
        assertThat(((PaymentError.TransportUnavailable) e).mayHaveBeenSent()).isFalse();
    }

    @Test
    void domainErrors_areNotRetryableByDefault() {
        assertThat(new PaymentError.IdempotencyConflict("x").isRetryableByDefault()).isFalse();
        assertThat(new PaymentError.ValidationFailure("x").isRetryableByDefault()).isFalse();
        assertThat(new PaymentError.NotFound("x").isRetryableByDefault()).isFalse();
        assertThat(new PaymentError.InternalFailure("x").isRetryableByDefault()).isFalse();
        assertThat(new PaymentError.AuthenticationFailure("x").isRetryableByDefault()).isFalse();
        assertThat(new PaymentError.AuthorizationFailure("x").isRetryableByDefault()).isFalse();
        assertThat(new PaymentError.PolicyRevisionConflict("x").isRetryableByDefault()).isFalse();
        assertThat(new PaymentError.PaymentLimitExceeded("x").isRetryableByDefault()).isFalse();
        assertThat(new PaymentError.PaymentLimitPolicyDisabled("x").isRetryableByDefault()).isFalse();
        assertThat(new PaymentError.PaymentLimitPolicyMissing("x").isRetryableByDefault()).isFalse();
    }

    @Test
    void categoriesAreDistinct() {
        assertThat(ErrorCategory.PENDING_RECONCILIATION).isNotEqualTo(ErrorCategory.TRANSPORT_UNAVAILABLE);
    }
}
