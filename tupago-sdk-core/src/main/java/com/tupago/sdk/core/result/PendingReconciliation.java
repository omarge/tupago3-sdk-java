package com.tupago.sdk.core.result;

import com.tupago.sdk.core.banxico.BanxicoMessage;

/**
 * CRITICAL FINANCIAL CONTRACT — the "unknown remote state" outcome.
 *
 * <p>The create call reached the dangerous crash/timeout window during the Banxico send and the
 * backend cannot confirm whether the charge was placed. This is NON-retryable and NON-resubmittable:
 * the caller MUST query status (by {@link #idc()}) instead of creating again.</p>
 *
 * <p>Backend representations (SDK-00): REST 202 / payment-pending-reconciliation, GraphQL
 * PAYMENT_PENDING_RECONCILIATION, gRPC ABORTED + TUPAGO_PENDING_RECONCILIATION.</p>
 *
 * @param <T> payload type of the enclosing result (unused here; kept for union consistency).
 */
public record PendingReconciliation<T>(String code, String message, BanxicoMessage payload, String idc)
        implements PaymentOperationResult<T> {

    public static final String CODE = "PAYMENT_PENDING_RECONCILIATION";

    public PendingReconciliation {
        if (code == null || message == null) {
            throw new IllegalArgumentException("PendingReconciliation code/message must not be null");
        }
    }

    /** Convenience factory from a backend reconciliation code, raw payload and the Banxico IDC. */
    public static <T> PendingReconciliation<T> of(String message, BanxicoMessage payload, String idc) {
        return new PendingReconciliation<>(CODE, message, payload, idc);
    }

    /** The only correct next action for the caller. */
    public String recommendedAction() {
        return "QUERY_STATUS_BY_IDC";
    }

    @Override
    public boolean retryAllowed() {
        return false;
    }
}
