package com.tupago.sdk.core.result;

import static org.assertj.core.api.Assertions.assertThat;

import com.tupago.sdk.core.banxico.BanxicoMessage;
import org.junit.jupiter.api.Test;

class PendingReconciliationTest {

    @Test
    void isNeverRetryable() {
        PendingReconciliation<Object> pr = PendingReconciliation.of(
                "payment pending reconciliation", BanxicoMessage.fromJson("{\"ic\":{\"IDC\":\"X1\"}}"), "X1");
        assertThat(pr.retryAllowed()).isFalse();
        assertThat(pr.isSuccess()).isFalse();
        assertThat(pr.code()).isEqualTo(PendingReconciliation.CODE);
        assertThat(pr.recommendedAction()).isEqualTo("QUERY_STATUS_BY_IDC");
        assertThat(pr.idc()).isEqualTo("X1");
    }

    @Test
    void carriesRawPayloadForReconciliation() {
        BanxicoMessage msg = BanxicoMessage.fromJson("{\"ic\":{\"IDC\":\"IDC-9\"},\"TYP\":1}");
        PendingReconciliation<Object> pr = PendingReconciliation.of("pending", msg, msg.icIdc());
        assertThat(pr.payload().icIdc()).isEqualTo("IDC-9");
        assertThat(pr.payload().typ()).isEqualTo(1);
    }
}
