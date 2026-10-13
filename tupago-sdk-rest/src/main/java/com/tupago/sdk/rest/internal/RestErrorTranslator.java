package com.tupago.sdk.rest.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.tupago.sdk.core.error.PaymentError;

/**
 * Translates a backend HTTP error response into the transport-neutral {@link PaymentError} taxonomy.
 *
 * <p>Resolution order: HTTP status first, then the RFC-7807 {@code type} URI for the business-specific
 * conflict/limit/policy conditions. The map is grounded in the backend's {@code ExceptionTranslator}
 * (anchor bbf06c02):
 * <ul>
 *   <li>401 -> AuthenticationFailure</li>
 *   <li>403 -> AuthorizationFailure</li>
 *   <li>404 -> NotFound</li>
 *   <li>202 pending-reconciliation is handled by the create adapters (it is a 2xx), never here.</li>
 *   <li>409 idempotency-conflict / policy-revision-conflict -> IdempotencyConflict / PolicyRevisionConflict</li>
 *   <li>400 co-di-amount-limit-exceeded / payment-limit-policy-* / constraint-violation -> respective PaymentError</li>
 *   <li>5xx -> TransportUnavailable (request provably reached the server, so callers must reconcile, never retry)</li>
 *   <li>other 4xx -> ValidationFailure (conservative, non-retryable)</li>
 * </ul>
 *
 * <p>No HTTP-framework type leaks: the result is always a core {@link PaymentError}.</p>
 */
public final class RestErrorTranslator {

    private RestErrorTranslator() {
    }

    static PaymentError translate(int status, JsonNode body) {
        String type = body != null ? textOrNull(body, "type") : null;
        String detail = body != null ? textOrNull(body, "detail") : null;
        String title = body != null ? textOrNull(body, "title") : null;
        String message = detail != null ? detail : (title != null ? title : ("HTTP " + status));

        if (status == 401) {
            return new PaymentError.AuthenticationFailure(message);
        }
        if (status == 403) {
            return new PaymentError.AuthorizationFailure(message);
        }
        if (status == 404) {
            return new PaymentError.NotFound(message);
        }
        if (status >= 500) {
            return new PaymentError.TransportUnavailable(true, message);
        }
        if (type != null) {
            String t = type.toLowerCase();
            if (t.contains("idempotency-conflict")) {
                return new PaymentError.IdempotencyConflict(message);
            }
            if (t.contains("policy-revision-conflict")) {
                return new PaymentError.PolicyRevisionConflict(message);
            }
            if (t.contains("co-di-amount-limit-exceeded")) {
                return new PaymentError.PaymentLimitExceeded(message);
            }
            if (t.contains("payment-limit-policy-disabled")) {
                return new PaymentError.PaymentLimitPolicyDisabled(message);
            }
            if (t.contains("payment-limit-policy-missing")) {
                return new PaymentError.PaymentLimitPolicyMissing(message);
            }
            if (t.contains("constraint-violation")) {
                return new PaymentError.ValidationFailure(message);
            }
        }
        if (status == 409) {
            // Both 409 variants are non-retryable conflicts; default safely to IdempotencyConflict.
            return new PaymentError.IdempotencyConflict(message);
        }
        if (status == 400) {
            return new PaymentError.ValidationFailure(message);
        }
        return new PaymentError.ValidationFailure(message);
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return v.isMissingNode() || v.isNull() ? null : v.asText();
    }
}
