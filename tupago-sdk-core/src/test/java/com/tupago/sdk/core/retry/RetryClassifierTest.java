package com.tupago.sdk.core.retry;

import static org.assertj.core.api.Assertions.assertThat;

import com.tupago.sdk.core.error.PaymentError;
import com.tupago.sdk.core.result.PaymentOperationResult;
import com.tupago.sdk.core.result.Rejected;
import com.tupago.sdk.core.result.Success;
import com.tupago.sdk.core.result.TransportFailure;
import org.junit.jupiter.api.Test;

class RetryClassifierTest {

    private final PaymentError transportNotSent = new PaymentError.TransportUnavailable(false, "reset");
    private final PaymentError transportSent = new PaymentError.TransportUnavailable(true, "timeout mid-flight");

    @Test
    void create_isNeverBlindRetried_evenIfRequestNeverLeftClient() {
        assertThat(RetryClassifier.classify(OperationKind.CREATE_QR, transportNotSent, false))
                .isEqualTo(RetryDecision.NO_RETRY);
        assertThat(RetryClassifier.classify(OperationKind.CREATE_TELEFONICO, transportSent, true))
                .isEqualTo(RetryDecision.NO_RETRY);
    }

    @Test
    void read_retriesOnlyOnProvenPreExecutionTransportFailure() {
        assertThat(RetryClassifier.classify(OperationKind.GET_QR_STATUS, transportNotSent, false))
                .isEqualTo(RetryDecision.RETRY);
        assertThat(RetryClassifier.classify(OperationKind.GET_CLIENTE, transportSent, true))
                .isEqualTo(RetryDecision.NO_RETRY);
    }

    @Test
    void pendingReconciliation_isNeverRetried() {
        PaymentError pending = new PaymentError.PendingReconciliation("ambiguous");
        assertThat(RetryClassifier.classify(OperationKind.CREATE_QR, pending, false))
                .isEqualTo(RetryDecision.NO_RETRY);
        assertThat(RetryClassifier.classify(OperationKind.GET_QR_STATUS, pending, true))
                .isEqualTo(RetryDecision.NO_RETRY);
    }

    @Test
    void domainErrors_areNeverRetried() {
        assertThat(RetryClassifier.classify(OperationKind.CREATE_QR,
                new PaymentError.IdempotencyConflict("x"), false)).isEqualTo(RetryDecision.NO_RETRY);
        assertThat(RetryClassifier.classify(OperationKind.GET_CLIENTE,
                new PaymentError.ValidationFailure("x"), false)).isEqualTo(RetryDecision.NO_RETRY);
        assertThat(RetryClassifier.classify(OperationKind.CREATE_QR,
                new PaymentError.PaymentLimitExceeded("x"), false)).isEqualTo(RetryDecision.NO_RETRY);
    }

    @Test
    void classifyByResult_matchesResultContract() {
        PaymentOperationResult<?> success = new Success<>("ok");
        PaymentOperationResult<?> rejected = new Rejected<>(new PaymentError.NotFound("x"));
        PaymentOperationResult<?> pending =
                com.tupago.sdk.core.result.PendingReconciliation.of("p", null, "IDC-1");
        PaymentOperationResult<?> tfRead = new TransportFailure<>(transportNotSent, false);
        PaymentOperationResult<?> tfCreate = new TransportFailure<>(transportNotSent, false);

        assertThat(RetryClassifier.classify(OperationKind.GET_CLIENTE, success)).isEqualTo(RetryDecision.NO_RETRY);
        assertThat(RetryClassifier.classify(OperationKind.CREATE_QR, rejected)).isEqualTo(RetryDecision.NO_RETRY);
        assertThat(RetryClassifier.classify(OperationKind.CREATE_QR, pending)).isEqualTo(RetryDecision.NO_RETRY);
        assertThat(RetryClassifier.classify(OperationKind.GET_CLIENTE, tfRead)).isEqualTo(RetryDecision.RETRY);
        // Create with a transport failure that never left the client: still NO_RETRY (no blind create retry).
        assertThat(RetryClassifier.classify(OperationKind.CREATE_QR, tfCreate)).isEqualTo(RetryDecision.NO_RETRY);
    }
}
