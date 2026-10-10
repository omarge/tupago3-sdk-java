package com.tupago.sdk.retry;

import com.tupago.sdk.error.StatusMapper;
import com.tupago.sdk.error.TupagoException;
import com.tupago.sdk.error.TupagoTransientException;
import io.grpc.Status;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RetryPolicyTest {

    @Test
    void noRetryNeverRetries() {
        NoRetryPolicy p = NoRetryPolicy.INSTANCE;
        TupagoException ex = StatusMapper.map(Status.UNAVAILABLE.withDescription("x").asRuntimeException());
        assertThat(p.shouldRetry(0, ex)).isFalse();
        assertThat(p.shouldRetry(5, ex)).isFalse();
    }

    @Test
    void backoffRetriesOnlyTransientWithinLimit() {
        BackoffRetryPolicy p = BackoffRetryPolicy.of(3);
        TupagoException transientErr = new TupagoTransientException(Status.UNAVAILABLE.withDescription("x"));
        assertThat(p.shouldRetry(0, transientErr)).isTrue();
        assertThat(p.shouldRetry(1, transientErr)).isTrue();
        assertThat(p.shouldRetry(2, transientErr)).isFalse(); // would be 3rd attempt
    }

    @Test
    void backoffNeverRetriesNonTransient() {
        BackoffRetryPolicy p = BackoffRetryPolicy.of(5);
        TupagoException alreadyExists = StatusMapper.map(Status.ALREADY_EXISTS.withDescription("dup").asRuntimeException());
        TupagoException pending = StatusMapper.map(
                Status.ABORTED.withDescription("TUPAGO_PENDING_RECONCILIATION; do not retry").asRuntimeException());
        assertThat(p.shouldRetry(0, alreadyExists)).isFalse();
        assertThat(p.shouldRetry(0, pending)).isFalse();
    }
}
