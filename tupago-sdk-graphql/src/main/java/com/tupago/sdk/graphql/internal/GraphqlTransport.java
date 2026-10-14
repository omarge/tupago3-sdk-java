package com.tupago.sdk.graphql.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tupago.sdk.core.auth.CredentialProvider;
import com.tupago.sdk.core.config.TimeoutConfig;
import com.tupago.sdk.core.error.PaymentError;
import com.tupago.sdk.core.retry.OperationKind;
import com.tupago.sdk.core.retry.RetryClassifier;
import com.tupago.sdk.core.retry.RetryDecision;
import com.tupago.sdk.core.retry.RetryPolicy;
import com.tupago.sdk.core.result.PaymentOperationResult;
import com.tupago.sdk.core.result.PendingReconciliation;
import com.tupago.sdk.core.result.Rejected;
import com.tupago.sdk.core.result.Success;
import com.tupago.sdk.core.result.TransportFailure;
import com.tupago.sdk.graphql.GraphqlClientConfig;
import java.io.IOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.channels.UnresolvedAddressException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import javax.net.ssl.SSLHandshakeException;

/**
 * Low-level GraphQL-over-HTTP execution engine (SDK-03).
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>Attach exactly {@code Authorization: Bearer <token>} from the {@link CredentialProvider}; never log it.</li>
 *   <li>Issue operations via the standard {@code {query, variables, operationName}} envelope over the JDK
 *       {@link HttpClient}; all caller data travels in {@code variables} (never interpolated into the
 *       query source) to prevent GraphQL injection (section 6 / 25).</li>
 *   <li>Apply {@link TimeoutConfig}: connect timeout on the client; per-request deadline = create-operation
 *       ceiling for creates, max(request, read) for reads.</li>
 *   <li>Never equate HTTP 200 with success: a GraphQL envelope with {@code errors:[]} is inspected before
 *       any {@code data} is trusted (section 12).</li>
 *   <li>Run the conservative retry loop driven solely by {@link RetryClassifier}: creates NEVER blind-retry;
 *       reads retry only on a proven pre-execution transport failure.</li>
 * </ul>
 *
 * <p>This class is internal: it references {@code java.net.http} and is never part of the public API.</p>
 */
public final class GraphqlTransport {

    /** Maps a 2xx GraphQL envelope's {@code data} node (and status) to a core result for a specific operation. */
    public interface SuccessOrPending<T> {
        PaymentOperationResult<T> apply(JsonNode data, int status);
    }

    /** Internal transport failure carrying a core error and "may have been sent" evidence. */
    private static final class GraphqlTransportException extends Exception {
        final PaymentError error;
        final boolean mayHaveBeenSent;

        GraphqlTransportException(PaymentError error, boolean mayHaveBeenSent) {
            super(error.message());
            this.error = error;
            this.mayHaveBeenSent = mayHaveBeenSent;
        }
    }

    private final GraphqlClientConfig config;
    private final ObjectMapper mapper;
    private final HttpClient client;
    private final CredentialProvider credentials;
    private final TimeoutConfig timeouts;
    private final RetryPolicy retryPolicy;

    public GraphqlTransport(GraphqlClientConfig config) {
        this.config = config;
        this.mapper = config.objectMapper();
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(config.timeouts().connectMillis()))
                .build();
        this.credentials = config.credentials();
        this.timeouts = config.timeouts();
        this.retryPolicy = config.retryPolicy();
    }

    public <T> PaymentOperationResult<T> exchange(OperationKind kind, String operationName, String queryDoc,
                                                  Map<String, Object> variables, SuccessOrPending<T> onSuccess) {
        String token = credentials.token();
        if (token == null || token.isBlank()) {
            return new Rejected<>(new PaymentError.AuthenticationFailure(
                    "No bearer token available from CredentialProvider"));
        }

        int maxAttempts = Math.max(1, retryPolicy.maxAttempts());

        // G4: a request-envelope serialization failure is LOCAL, DETERMINISTIC and NOT a transport
        // failure. It must fail closed as an InternalFailure BEFORE any network attempt and MUST NOT be
        // classified as a retryable TransportUnavailable (which could, for a read, legitimately retry).
        byte[] body;
        try {
            body = buildEnvelope(operationName, queryDoc, variables);
        } catch (Exception e) {
            return new Rejected<>(new PaymentError.InternalFailure("Failed to serialize GraphQL request"));
        }

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return sendOnce(kind, token, body, onSuccess);
            } catch (GraphqlTransportException te) {
                // RetryClassifier is the SINGLE semantic retry authority. The loop only enforces the
                // structural attempt cap; it does NOT decide financial retryability itself.
                TransportFailure<T> result = new TransportFailure<>(te.error, te.mayHaveBeenSent);
                RetryDecision decision = RetryClassifier.classify(kind, result);
                if (decision != RetryDecision.RETRY || attempt == maxAttempts) {
                    return result;
                }
                try {
                    Thread.sleep(Math.max(0, retryPolicy.baseDelay().toMillis()));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return result;
                }
            }
        }
        // Unreachable: loop always returns.
        return new TransportFailure<>(new PaymentError.TransportUnavailable(true, "unreachable retry loop"), true);
    }

    private <T> PaymentOperationResult<T> sendOnce(OperationKind kind, String token, byte[] body,
                                                   SuccessOrPending<T> onSuccess) throws GraphqlTransportException {
        HttpRequest.Builder rb = HttpRequest.newBuilder()
                .uri(resolveUri())
                .timeout(requestTimeout(kind))
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body));

        HttpResponse<byte[]> resp;
        try {
            resp = client.send(rb.build(), HttpResponse.BodyHandlers.ofByteArray());
        } catch (Exception e) {
            throw classify(e);
        }
        return handleResponse(kind, resp, onSuccess);
    }

    private byte[] buildEnvelope(String operationName, String queryDoc, Map<String, Object> variables) throws IOException {
        // Variables are serialized by Jackson; caller data is NEVER concatenated into queryDoc.
        return mapper.writeValueAsBytes(Map.of(
                "query", queryDoc,
                "variables", variables,
                "operationName", operationName));
    }

    private <T> PaymentOperationResult<T> handleResponse(
            OperationKind kind, HttpResponse<byte[]> resp, SuccessOrPending<T> onSuccess) {
        int status = resp.statusCode();
        if (status < 200 || status >= 300) {
            return handleHttpError(status, resp.body());
        }
        // Strict parse: a malformed/empty 2xx body is a contract violation and MUST fail closed (B3/B21).
        JsonNode root;
        try {
            root = readTreeStrict(resp.body());
        } catch (RuntimeException e) {
            if (kind.isCreate()) {
                return new TransportFailure<>(
                        new PaymentError.TransportUnavailable(
                                true,
                                "Ambiguous GraphQL create envelope: " + e.getClass().getSimpleName()),
                        true);
            }
            return new Rejected<>(new PaymentError.InternalFailure(
                    "Malformed GraphQL envelope: " + e.getClass().getSimpleName()));
        }
        JsonNode errors = root.path("errors");
        if (errors.isArray() && errors.size() > 0) {
            // Never silently accept partial data when a material error exists (section 15).
            PaymentOperationResult<T> errorResult = handleGraphQLErrors(errors);
            // A recognized deterministic backend error remains deterministic. An unknown/unclassified
            // application error after a financial create is ambiguous: the mutation may already have run.
            if (kind.isCreate()
                    && errorResult instanceof Rejected<?> rejected
                    && rejected.error() instanceof PaymentError.InternalFailure internal) {
                return new TransportFailure<>(
                        new PaymentError.TransportUnavailable(
                                true, "Ambiguous GraphQL create error: " + internal.message()),
                        true);
            }
            return errorResult;
        }
        JsonNode data = root.path("data");
        if (data.isMissingNode() || data.isNull()) {
            if (kind.isCreate()) {
                return new TransportFailure<>(
                        new PaymentError.TransportUnavailable(
                                true, "Ambiguous GraphQL create response: missing data"),
                        true);
            }
            return new Rejected<>(new PaymentError.InternalFailure("GraphQL response missing 'data'"));
        }
        try {
            return onSuccess.apply(data, status);
        } catch (RuntimeException e) {
            if (kind.isCreate()) {
                return new TransportFailure<>(
                        new PaymentError.TransportUnavailable(
                                true,
                                "Ambiguous GraphQL create data: " + e.getClass().getSimpleName()),
                        true);
            }
            return new Rejected<>(new PaymentError.InternalFailure(
                    "Malformed GraphQL data payload: " + e.getClass().getSimpleName()));
        }
    }

    /**
     * Resolve GraphQL application errors. {@code PAYMENT_PENDING_RECONCILIATION} dominates any apparent
     * partial data and maps to {@link PendingReconciliation} (never retried, never resubmitted). Any other
     * error maps to a {@link Rejected} of the appropriate {@link PaymentError}; unknown codes fail closed.
     */
    private <T> PaymentOperationResult<T> handleGraphQLErrors(JsonNode errors) {
        String pendingMessage = null;
        for (JsonNode err : errors) {
            if (GraphqlErrorTranslator.CODE_PAYMENT_PENDING_RECONCILIATION.equals(GraphqlErrorTranslator.extractCode(err))) {
                pendingMessage = GraphqlErrorTranslator.extractMessage(err, "Payment operation pending reconciliation");
                break;
            }
        }
        if (pendingMessage != null) {
            return new PendingReconciliation<>(PendingReconciliation.CODE, pendingMessage, null, null);
        }
        for (JsonNode err : errors) {
            String code = GraphqlErrorTranslator.extractCode(err);
            if (code != null) {
                return new Rejected<>(GraphqlErrorTranslator.errorForCode(
                        code, GraphqlErrorTranslator.extractMessage(err, code)));
            }
        }
        return new Rejected<>(new PaymentError.InternalFailure("GraphQL errors without a stable code extension"));
    }

    private <T> PaymentOperationResult<T> handleHttpError(int status, byte[] body) {
        JsonNode root = readTreeOrNull(body);
        if (root != null && root.has("errors")) {
            JsonNode errors = root.get("errors");
            if (errors.isArray() && errors.size() > 0) {
                return handleGraphQLErrors(errors);
            }
        }
        PaymentError error = GraphqlErrorTranslator.errorForStatus(status, httpStatusMessage(root, status));
        if (error instanceof PaymentError.TransportUnavailable tu) {
            return new TransportFailure<>(tu, true);
        }
        return new Rejected<>(error);
    }

    private static String httpStatusMessage(JsonNode root, int status) {
        if (root != null) {
            JsonNode msg = root.path("message");
            if (!msg.isMissingNode() && !msg.isNull() && !msg.asText().isBlank()) {
                return "HTTP " + status + ": " + msg.asText();
            }
        }
        return "HTTP " + status;
    }

    private GraphqlTransportException classify(Exception e) {
        boolean mayHaveBeenSent = true; // conservative: assume the request may have reached the backend
        if (e instanceof HttpTimeoutException) {
            mayHaveBeenSent = true;
        } else if (e instanceof ConnectException || e instanceof UnknownHostException
                || e instanceof NoRouteToHostException || e instanceof UnresolvedAddressException) {
            mayHaveBeenSent = false;
        } else if (e instanceof SSLHandshakeException) {
            mayHaveBeenSent = false;
        } else if (e instanceof InterruptedException) {
            Thread.currentThread().interrupt();
            mayHaveBeenSent = true;
        }
        return new GraphqlTransportException(
                new PaymentError.TransportUnavailable(mayHaveBeenSent, "Transport failure: " + e.getClass().getSimpleName()),
                mayHaveBeenSent);
    }

    private URI resolveUri() {
        String base = config.baseUri().toString().replaceAll("/+$", "");
        String path = config.graphqlPath();
        return URI.create(base + path);
    }

    private Duration requestTimeout(OperationKind kind) {
        if (kind.isCreate()) {
            long ms = timeouts.createOperationMillis() > 0 ? timeouts.createOperationMillis() : timeouts.requestMillis();
            return Duration.ofMillis(ms);
        }
        return Duration.ofMillis(Math.max(timeouts.requestMillis(), timeouts.readMillis()));
    }

    private JsonNode readTreeStrict(byte[] body) {
        if (body == null || body.length == 0) {
            throw new IllegalStateException("Empty success body");
        }
        try {
            JsonNode node = mapper.readTree(body);
            if (node == null || node.isMissingNode()) {
                throw new IllegalStateException("Null JSON node in success body");
            }
            return node;
        } catch (IOException e) {
            throw new IllegalStateException("Malformed JSON success body", e);
        }
    }

    private JsonNode readTreeOrNull(byte[] body) {
        if (body == null || body.length == 0) {
            return null;
        }
        try {
            return mapper.readTree(body);
        } catch (IOException e) {
            return null;
        }
    }
}
