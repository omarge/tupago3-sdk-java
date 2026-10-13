package com.tupago.sdk.core.result;

import static org.assertj.core.api.Assertions.assertThat;

import com.tupago.sdk.core.error.PaymentError;
import org.junit.jupiter.api.Test;

class ResultExhaustivenessTest {

    /** Sealed switch — the compiler enforces exhaustiveness across Success/PendingReconciliation/Rejected/TransportFailure. */
    private static boolean mayRetry(PaymentOperationResult<?> r) {
        return switch (r) {
            case Success<?> s -> s.retryAllowed();
            case PendingReconciliation<?> p -> p.retryAllowed();
            case Rejected<?> rj -> rj.retryAllowed();
            case TransportFailure<?> tf -> tf.retryAllowed();
        };
    }

    @Test
    void exhaustiveSwitchReflectsContract() {
        assertThat(mayRetry(new Success<>("ok"))).isFalse();
        assertThat(mayRetry(com.tupago.sdk.core.result.PendingReconciliation.of("p", null, "X")))
                .isFalse();
        assertThat(mayRetry(new Rejected<>(new PaymentError.NotFound("x")))).isFalse();
        assertThat(mayRetry(new TransportFailure<>(new PaymentError.TransportUnavailable(false, "r"), false)))
                .isTrue();
        assertThat(mayRetry(new TransportFailure<>(new PaymentError.TransportUnavailable(true, "r"), true)))
                .isFalse();
    }
}
