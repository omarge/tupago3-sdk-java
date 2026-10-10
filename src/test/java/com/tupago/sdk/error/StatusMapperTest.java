package com.tupago.sdk.error;

import io.grpc.Status;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StatusMapperTest {

    @Test
    void mapsAllFrozenCodes() {
        assertThat(StatusMapper.map(Status.UNAUTHENTICATED.withDescription("m").asRuntimeException()))
                .isInstanceOf(TupagoUnauthenticatedException.class);
        assertThat(StatusMapper.map(Status.PERMISSION_DENIED.withDescription("m").asRuntimeException()))
                .isInstanceOf(TupagoPermissionDeniedException.class);
        assertThat(StatusMapper.map(Status.INVALID_ARGUMENT.withDescription("m").asRuntimeException()))
                .isInstanceOf(TupagoInvalidArgumentException.class);
        assertThat(StatusMapper.map(Status.ALREADY_EXISTS.withDescription("m").asRuntimeException()))
                .isInstanceOf(TupagoAlreadyExistsException.class);
        assertThat(StatusMapper.map(Status.FAILED_PRECONDITION.withDescription("m").asRuntimeException()))
                .isInstanceOf(TupagoFailedPreconditionException.class);
        assertThat(StatusMapper.map(Status.NOT_FOUND.withDescription("m").asRuntimeException()))
                .isInstanceOf(TupagoNotFoundException.class);
        assertThat(StatusMapper.map(Status.INTERNAL.withDescription("m").asRuntimeException()))
                .isInstanceOf(TupagoInternalException.class);
        assertThat(StatusMapper.map(Status.UNAVAILABLE.withDescription("m").asRuntimeException()))
                .isInstanceOf(TupagoTransientException.class);
    }

    @Test
    void abortedWithPendingReconciliationIsNonTransient() {
        Status status = Status.ABORTED.withDescription(
                "TUPAGO_PENDING_RECONCILIATION; payment pending reconciliation; do not retry; query status first");
        TupagoException ex = StatusMapper.map(status.asRuntimeException());
        assertThat(ex).isInstanceOf(TupagoAbortedException.class);
        assertThat(ex.isPendingReconciliation()).isTrue();
        assertThat(ex.isRetryable()).isFalse();
    }

    @Test
    void transientIsRetryable() {
        TupagoException ex = StatusMapper.map(Status.UNAVAILABLE.withDescription("boom").asRuntimeException());
        assertThat(ex.isRetryable()).isTrue();
    }

    @Test
    void preservesCodeAndDescription() {
        Status status = Status.ALREADY_EXISTS.withDescription("dup-1");
        TupagoException ex = StatusMapper.map(status.asRuntimeException());
        assertThat(ex.code()).isEqualTo(Status.Code.ALREADY_EXISTS);
        assertThat(ex.description()).isEqualTo("dup-1");
    }
}
