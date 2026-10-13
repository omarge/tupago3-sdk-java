package com.tupago.sdk.graphql.internal;

import com.tupago.sdk.core.error.PaymentError;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Single, authoritative translator from GraphQL failure representations into the transport-neutral
 * {@link PaymentError} taxonomy (SDK-03, section 13). No GraphQL response/error class leaks past the
 * transport boundary.
 *
 * <p>Two failure channels are normalized here:
 * <ul>
 *   <li><b>GraphQL application errors</b> ({@code 200 + {errors:[{extensions:{code}}]}}): the stable
 *       {@code code} extension is mapped. Unknown codes fail closed as {@link PaymentError.InternalFailure}.</li>
 *   <li><b>HTTP transport/security failures</b> (non-2xx): mapped by status code. A 5xx is a
 *       {@link PaymentError.TransportUnavailable} (retry candidate); auth/validation/not-found become
 *       {@code Rejected}.</li>
 * </ul>
 *
 * <p>{@code PAYMENT_PENDING_RECONCILIATION} is intentionally NOT an ordinary error: the transport treats
 * it as {@link com.tupago.sdk.core.result.PendingReconciliation} directly (see {@code GraphqlTransport}).</p>
 *
 * <p><b>Authoritative backend code taxonomy (GraphQlConfig.tupagoGraphQlErrorResolver, audited against master
 * {@code bbf06c02}):</b> the backend currently emits only
 * {@code IDEMPOTENCY_CONFLICT}, {@code POLICY_REVISION_CONFLICT}, {@code PAYMENT_LIMIT} and
 * {@code PAYMENT_PENDING_RECONCILIATION}. All three amount-limit exceptions
 * ({@code CoDiAmountLimitExceededException}, {@code PaymentLimitPolicyDisabledException},
 * {@code PaymentLimitPolicyMissingException}) are collapsed by the backend into the single
 * {@code PAYMENT_LIMIT} code (classification {@code bad_request}). This is a BACKEND CONTRACT LIMITATION:
 * the SDK cannot distinguish amount-exceeded vs policy-disabled vs policy-missing from the stable code alone,
 * and MUST NOT inspect the error message text to guess the original exception, nor invent heuristic
 * classifications. The SDK therefore maps {@code PAYMENT_LIMIT} -> {@link PaymentError.PaymentLimitExceeded}
 * as the authoritative behavior. The codes {@code PAYMENT_LIMIT_POLICY_DISABLED} / {@code PAYMENT_LIMIT_POLICY_MISSING}
 * are retained ONLY as forward-compatible aliases (they are NOT currently emitted by the backend) so a future
 * backend that emits them verbatim is handled without a code change. Tests for those aliases document them as
 * compatibility-only, never as proof of current backend behavior.</p>
 */
public final class GraphqlErrorTranslator {

    static final String CODE_IDEMPOTENCY_CONFLICT = "IDEMPOTENCY_CONFLICT";
    static final String CODE_POLICY_REVISION_CONFLICT = "POLICY_REVISION_CONFLICT";
    static final String CODE_PAYMENT_LIMIT = "PAYMENT_LIMIT";
    static final String CODE_PAYMENT_LIMIT_POLICY_DISABLED = "PAYMENT_LIMIT_POLICY_DISABLED";
    static final String CODE_PAYMENT_LIMIT_POLICY_MISSING = "PAYMENT_LIMIT_POLICY_MISSING";
    static final String CODE_PAYMENT_PENDING_RECONCILIATION = "PAYMENT_PENDING_RECONCILIATION";

    private GraphqlErrorTranslator() {
    }

    /** Map a stable GraphQL error {@code code} extension to a {@link PaymentError}; unknown -> InternalFailure.
     *  See class javadoc for the authoritative backend code taxonomy and the PAYMENT_LIMIT semantic-collapse residual. */
    static PaymentError errorForCode(String code, String message) {
        String msg = (message == null || message.isBlank()) ? defaultMessage(code) : message;
        if (code == null) {
            return new PaymentError.InternalFailure("GraphQL error without a stable code extension");
        }
        return switch (code) {
            case CODE_IDEMPOTENCY_CONFLICT -> new PaymentError.IdempotencyConflict(msg);
            case CODE_POLICY_REVISION_CONFLICT -> new PaymentError.PolicyRevisionConflict(msg);
            case CODE_PAYMENT_LIMIT -> new PaymentError.PaymentLimitExceeded(msg);
            case CODE_PAYMENT_LIMIT_POLICY_DISABLED -> new PaymentError.PaymentLimitPolicyDisabled(msg);
            case CODE_PAYMENT_LIMIT_POLICY_MISSING -> new PaymentError.PaymentLimitPolicyMissing(msg);
            default -> new PaymentError.InternalFailure("Unrecognized GraphQL error code: " + code);
        };
    }

    /** Map an HTTP status (non-2xx, no GraphQL error envelope) into a {@link PaymentError}. */
    static PaymentError errorForStatus(int status, String message) {
        String msg = (message == null || message.isBlank()) ? ("HTTP " + status) : message;
        if (status == 401) {
            return new PaymentError.AuthenticationFailure(msg);
        }
        if (status == 403) {
            return new PaymentError.AuthorizationFailure(msg);
        }
        if (status == 404) {
            return new PaymentError.NotFound(msg);
        }
        if (status >= 400 && status < 500) {
            return new PaymentError.ValidationFailure(msg);
        }
        if (status >= 500) {
            return new PaymentError.TransportUnavailable(true, msg);
        }
        return new PaymentError.InternalFailure(msg);
    }

    /** Extract the stable {@code code} from a GraphQL error node, or null if absent. */
    static String extractCode(JsonNode error) {
        JsonNode ext = error.path("extensions");
        if (ext.isMissingNode() || ext.isNull()) {
            return null;
        }
        JsonNode code = ext.path("code");
        return code.isMissingNode() || code.isNull() ? null : code.asText();
    }

    /** Best-effort human message for an error node (extensions.message or message). */
    static String extractMessage(JsonNode error, String fallback) {
        JsonNode msg = error.path("message");
        if (!msg.isMissingNode() && !msg.isNull() && !msg.asText().isBlank()) {
            return msg.asText();
        }
        return fallback;
    }

    private static String defaultMessage(String code) {
        return code == null ? "GraphQL error" : code;
    }
}
