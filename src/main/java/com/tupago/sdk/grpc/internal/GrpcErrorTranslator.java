package com.tupago.sdk.grpc.internal;

import com.tupago.sdk.core.error.PaymentError;
import com.tupago.sdk.core.result.PaymentOperationResult;
import com.tupago.sdk.core.result.PendingReconciliation;
import com.tupago.sdk.core.result.Rejected;
import com.tupago.sdk.core.result.TransportFailure;
import com.tupago.sdk.core.retry.OperationKind;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;

/**
 * Translates a gRPC {@link StatusRuntimeException} into the transport-neutral core result union.
 *
 * <p>Authoritative backend matrix (TupagoGrpcService.toStatus @ bbf06c02):
 * <ul>
 *   <li>UNAUTHENTICATED -&gt; {@link PaymentError.AuthenticationFailure}</li>
 *   <li>PERMISSION_DENIED -&gt; {@link PaymentError.AuthorizationFailure}</li>
 *   <li>INVALID_ARGUMENT -&gt; {@link PaymentError.ValidationFailure}</li>
 *   <li>NOT_FOUND -&gt; {@link PaymentError.NotFound}</li>
 *   <li>INTERNAL -&gt; {@link PaymentError.InternalFailure}</li>
 *   <li>ALREADY_EXISTS -&gt; {@link PaymentError.InternalFailure} (semantic-collapse residual: backend does
 *       NOT distinguish IdempotencyConflict from PolicyRevisionConflict on gRPC; we must not falsely
 *       claim either one — see SDK-04 report residual).</li>
 *   <li>FAILED_PRECONDITION -&gt; {@link PaymentError.PaymentLimitExceeded} (three backend exceptions collapse
 *       here: CoDiAmountLimitExceeded / PaymentLimitPolicyDisabled / PaymentLimitPolicyMissing).</li>
 *   <li>ABORTED + {@code TUPAGO_PENDING_RECONCILIATION} -&gt; {@link PendingReconciliation} (never retry).</li>
 *   <li>ABORTED without the stable pending code -&gt; {@link PaymentError.InternalFailure} (fail closed).</li>
 *   <li>UNAVAILABLE / DEADLINE_EXCEEDED -&gt; {@link TransportFailure} (conservative mayHaveBeenSent = true).</li>
 * </ul>
 *
 * <p>This translator NEVER inspects human-readable descriptions to guess a domain subtype; it branches only
 * on the stable gRPC status code and the single stable machine token
 * {@link #PENDING_RECONCILIATION_CODE}.</p>
 */
final class GrpcErrorTranslator {

    /** Stable code embedded by the backend in the ABORTED status description (gate G6 / F07-R2). */
    static final String PENDING_RECONCILIATION_CODE = "TUPAGO_PENDING_RECONCILIATION";

    private GrpcErrorTranslator() {
    }

    static <T> PaymentOperationResult<T> translate(OperationKind kind, StatusRuntimeException sre, String pendingIdc) {
        Status status = sre.getStatus();
        String description = status.getDescription() == null ? "" : status.getDescription();

        switch (status.getCode()) {
            case UNAUTHENTICATED:
                return new Rejected<>(new PaymentError.AuthenticationFailure(strip(description)));
            case PERMISSION_DENIED:
                return new Rejected<>(new PaymentError.AuthorizationFailure(strip(description)));
            case INVALID_ARGUMENT:
                return new Rejected<>(new PaymentError.ValidationFailure(strip(description)));
            case NOT_FOUND:
                return new Rejected<>(new PaymentError.NotFound(strip(description)));
            case INTERNAL:
                return new Rejected<>(new PaymentError.InternalFailure(strip(description)));
            case ALREADY_EXISTS:
                // Semantic-collapse residual: cannot tell idempotency vs policy-revision conflict.
                return new Rejected<>(new PaymentError.InternalFailure(
                        "gRPC ALREADY_EXISTS (idempotency or policy-revision conflict; backend does not disambiguate): "
                                + strip(description)));
            case FAILED_PRECONDITION:
                return new Rejected<>(new PaymentError.PaymentLimitExceeded(strip(description)));
            case ABORTED:
                if (description.contains(PENDING_RECONCILIATION_CODE)) {
                    return PendingReconciliation.of(
                            "Payment pending reconciliation; do not retry; query status first", null, pendingIdc);
                }
                // ABORTED without the stable pending token must NOT become PendingReconciliation.
                return new Rejected<>(new PaymentError.InternalFailure(
                        "gRPC ABORTED without PENDING_RECONCILIATION token: " + strip(description)));
            case UNAVAILABLE:
            case DEADLINE_EXCEEDED:
                // Conservative: a gRPC status alone does not prove whether the RPC reached execution.
                // mayHaveBeenSent = true => creates never retry, reads retry only on proven pre-exec failure
                // (which gRPC does not expose here), so this effectively disables speculative retry.
                return new TransportFailure<>(
                        new PaymentError.TransportUnavailable(true, strip(description)), true);
            default:
                return new Rejected<>(new PaymentError.InternalFailure(
                        "Unmapped gRPC status " + status.getCode() + ": " + strip(description)));
        }
    }

    /** Avoid echoing long server descriptions that could carry PII; keep a bounded message. */
    private static String strip(String s) {
        if (s == null) {
            return "";
        }
        String t = s.length() > 400 ? s.substring(0, 400) : s;
        return t.replaceAll("[\r\n]+", " ").trim();
    }
}
