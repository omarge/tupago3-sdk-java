package com.tupago.sdk.rest.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tupago.sdk.core.auth.CredentialProvider;
import com.tupago.sdk.core.banxico.BanxicoMessage;
import com.tupago.sdk.core.config.TimeoutConfig;
import com.tupago.sdk.core.error.PaymentError;
import com.tupago.sdk.core.idempotency.IdempotencyKey;
import com.tupago.sdk.core.retry.OperationKind;
import com.tupago.sdk.core.retry.RetryClassifier;
import com.tupago.sdk.core.retry.RetryDecision;
import com.tupago.sdk.core.retry.RetryPolicy;
import com.tupago.sdk.core.result.PaymentOperationResult;
import com.tupago.sdk.core.result.Rejected;
import com.tupago.sdk.core.result.Success;
import com.tupago.sdk.core.result.TransportFailure;
import com.tupago.sdk.rest.RestClientConfig;
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
import java.util.function.BiFunction;
import javax.net.ssl.SSLHandshakeException;

/**
 * Low-level REST execution engine (SDK-02).
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>Attach exactly {@code Authorization: Bearer <token>} from the {@link CredentialProvider}; never log it.</li>
 *   <li>Apply {@link TimeoutConfig}: connect timeout on the {@link HttpClient}; per-request deadline
 *       = create-operation ceiling for creates, max(request, read) for reads (java.net.http exposes only
 *       connect + overall-request timeouts, so readMillis is folded into the request deadline).</li>
 *   <li>Optionally attach {@code Idempotency-Key} (omitted for legacy/unsafe keys, forcing backend
 *       per-request dedup and forbidding SDK auto-retry).</li>
 *   <li>Translate responses/errors into the core result/error union, and run the conservative retry loop
 *       driven by {@link com.tupago.sdk.core.retry.RetryClassifier}: creates NEVER blind-retry; reads
 *       retry only on a proven pre-execution transport failure.</li>
 * </ul>
 *
 * <p>This class is internal: it references {@code java.net.http} and is never part of the public API.</p>
 */
public final class RestTransport {

    /** Maps a 2xx response body (and status) to a core result for a specific operation. */
    public interface SuccessOrPending<T> {
        PaymentOperationResult<T> apply(JsonNode node, int status);
    }

    private final RestClientConfig config;
    private final ObjectMapper mapper;
    private final HttpClient client;
    private final CredentialProvider credentials;
    private final TimeoutConfig timeouts;
    private final RetryPolicy retryPolicy;

    public RestTransport(RestClientConfig config) {
        this.config = config;
        this.mapper = config.objectMapper();
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(config.timeouts().connectMillis()))
                .build();
        this.credentials = config.credentials();
        this.timeouts = config.timeouts();
        this.retryPolicy = config.retryPolicy();
    }

    public <T> PaymentOperationResult<T> exchange(OperationKind kind, String method, String path,
                                           Object requestBody, IdempotencyKey idempotencyKey,
                                           SuccessOrPending<T> onSuccess) {
        String token = credentials.token();
        if (token == null || token.isBlank()) {
            return new Rejected<>(new PaymentError.AuthenticationFailure(
                    "No bearer token available from CredentialProvider"));
        }

        byte[] serializedBody = null;
        if (requestBody != null) {
            try {
                serializedBody = mapper.writeValueAsBytes(requestBody);
            } catch (Exception e) {
                // Local deterministic failure before any network attempt. This is not a
                // TransportUnavailable condition and must never enter the read retry loop.
                return new Rejected<>(new PaymentError.InternalFailure("Failed to serialize REST request body"));
            }
        }

        int maxAttempts = Math.max(1, retryPolicy.maxAttempts());
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return sendOnce(kind, method, path, serializedBody, idempotencyKey, token, onSuccess);
            } catch (RestTransportException te) {
                // RetryClassifier (SDK-01) is the SINGLE semantic retry authority. The loop only enforces
                // the structural attempt cap; it does NOT decide financial retryability itself.
                TransportFailure<T> result = new TransportFailure<>(te.error(), te.mayHaveBeenSent());
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

    private <T> PaymentOperationResult<T> sendOnce(OperationKind kind, String method, String path,
                                                  byte[] requestBody, IdempotencyKey idempotencyKey,
                                                  String token, SuccessOrPending<T> onSuccess)
            throws RestTransportException {
        HttpRequest.Builder rb = HttpRequest.newBuilder()
                .uri(resolveUri(path))
                .timeout(requestTimeout(kind))
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/json");

        if (requestBody != null) {
            rb.header("Content-Type", "application/json");
            rb.method(method, HttpRequest.BodyPublishers.ofByteArray(requestBody));
        } else {
            rb.method(method, HttpRequest.BodyPublishers.noBody());
        }

        if (idempotencyKey != null && !idempotencyKey.isLegacyUnsafe()) {
            rb.header("Idempotency-Key", idempotencyKey.value());
        }

        HttpResponse<byte[]> resp;
        try {
            resp = client.send(rb.build(), HttpResponse.BodyHandlers.ofByteArray());
        } catch (Exception e) {
            throw classify(e);
        }
        return handleResponse(kind, resp, onSuccess);
    }

    private <T> PaymentOperationResult<T> handleResponse(OperationKind kind, HttpResponse<byte[]> resp, SuccessOrPending<T> onSuccess) {
        int status = resp.statusCode();
        if (status >= 200 && status < 300) {
            // Strict parse: a malformed/empty 2xx body is a contract violation and MUST fail closed,
            // never be coerced into an empty object (B3).
            try {
                JsonNode node = readTreeStrict(resp.body());
                return onSuccess.apply(node, status);
            } catch (RuntimeException e) {
                if (kind.isCreate()) {
                    return new TransportFailure<>(
                            new PaymentError.TransportUnavailable(
                                    true, "Malformed successful create response body: " + e.getClass().getSimpleName()),
                            true);
                }
                return new Rejected<>(new PaymentError.InternalFailure(
                        "Malformed successful response body: " + e.getClass().getSimpleName()));
            }
        }
        // Never follow redirects for financial creates. A 3xx after dispatch is ambiguous:
        // the backend or an intermediary may already have executed the payment.
        if (kind.isCreate() && status >= 300 && status < 400) {
            return new TransportFailure<>(
                    new PaymentError.TransportUnavailable(true, "Unexpected redirect after create dispatch: HTTP " + status),
                    true);
        }
        // Error bodies are parsed tolerantly: the HTTP status code is authoritative for classification.
        PaymentError error = RestErrorTranslator.translate(status, readTreeOrNull(resp.body()));
        if (error instanceof PaymentError.TransportUnavailable tu) {
            return new TransportFailure<>(tu, true);
        }
        return new Rejected<>(error);
    }

    private RestTransportException classify(Exception e) {
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
        return new RestTransportException(
                new PaymentError.TransportUnavailable(mayHaveBeenSent, "Transport failure: " + e.getClass().getSimpleName()),
                mayHaveBeenSent, e);
    }

    private URI resolveUri(String path) {
        String base = config.baseUri().toString().replaceAll("/+$", "");
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
