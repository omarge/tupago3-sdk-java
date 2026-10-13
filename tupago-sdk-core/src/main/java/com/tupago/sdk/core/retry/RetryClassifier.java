package com.tupago.sdk.core.retry;

import com.tupago.sdk.core.error.ErrorCategory;
import com.tupago.sdk.core.error.PaymentError;
import com.tupago.sdk.core.result.PaymentOperationResult;
import com.tupago.sdk.core.result.PendingReconciliation;
import com.tupago.sdk.core.result.Rejected;
import com.tupago.sdk.core.result.Success;
import com.tupago.sdk.core.result.TransportFailure;

/**
 * Central, explicit retry classification (SDK-01, resolves gaps G1 + the financial safety rules in
 * sections 13/14). This is the SINGLE authority for retry decisions; transports must consult it and
 * must NOT invent their own retry heuristics.
 *
 * <h3>Rules</h3>
 * <ul>
 *   <li><b>PendingReconciliation</b> (unknown remote state): <b>NEVER</b> retry. Reconcile by status query.</li>
 *   <li><b>IdempotencyConflict, PolicyRevisionConflict, Validation, Auth(Auth/AUTHZ), PaymentLimit*, NotFound, Internal</b>:
 *       <b>NEVER</b> retry.</li>
 *   <li><b>TransportUnavailable</b>:
 *       <ul>
 *         <li>On a <b>CREATE</b>: <b>NEVER</b> blind-retry. (Even if the request provably never left the
 *             client, a create must not be auto-resubmitted by the SDK; the caller may retry manually with a
 *             NEW idempotency key, but that is an explicit business decision, not an SDK blind retry.)</li>
 *         <li>On a <b>READ</b>: retry ONLY when {@code mayHaveBeenSent == false} (proven pre-execution
 *             transport failure). If the request may have been sent, reconcile instead.</li>
 *       </ul>
 *   </li>
 *   <li><b>Success</b>: nothing to retry.</li>
 * </ul>
 */
public final class RetryClassifier {

    private RetryClassifier() {
    }

    /** Classify based on the already-wrapped operation result. */
    public static RetryDecision classify(OperationKind operation, PaymentOperationResult<?> result) {
        if (result instanceof Success<?>) {
            return RetryDecision.NO_RETRY;
        }
        if (result instanceof PendingReconciliation<?>) {
            return RetryDecision.NO_RETRY;
        }
        if (result instanceof Rejected<?>) {
            return RetryDecision.NO_RETRY;
        }
        if (result instanceof TransportFailure<?> tf) {
            return classify(operation, tf.error(), tf.mayHaveBeenSent());
        }
        return RetryDecision.NO_RETRY;
    }

    /**
     * Classify based on a raw error (used by transport adapters before wrapping).
     *
     * @param mayHaveBeenSent evidence from the transport: {@code true} means the request may have reached
     *                        the backend (ambiguous for creates -> reconcile, never blind retry).
     */
    public static RetryDecision classify(OperationKind operation, PaymentError error, boolean mayHaveBeenSent) {
        ErrorCategory category = error.category();
        if (category == ErrorCategory.PENDING_RECONCILIATION) {
            return RetryDecision.NO_RETRY;
        }
        if (category != ErrorCategory.TRANSPORT_UNAVAILABLE) {
            // Domain / auth / validation / conflict / not-found / internal: never retry.
            return RetryDecision.NO_RETRY;
        }
        // TransportUnavailable.
        if (operation.isCreate()) {
            // No blind retry for creates, regardless of mayHaveBeenSent.
            return RetryDecision.NO_RETRY;
        }
        // Reads: safe to retry only if provably pre-execution.
        return mayHaveBeenSent ? RetryDecision.NO_RETRY : RetryDecision.RETRY;
    }
}
