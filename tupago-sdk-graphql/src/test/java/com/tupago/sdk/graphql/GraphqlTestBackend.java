package com.tupago.sdk.graphql;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Minimal in-process HTTP backend used to exercise the GraphQL transport against real wire behavior
 * (status codes, headers, GraphQL envelopes) without network dependencies. Serves every path (so the
 * {@code /graphql} endpoint matches) and returns a scripted GraphQL envelope.
 */
final class GraphqlTestBackend {

    record RecordedRequest(String method, String path, Map<String, List<String>> headers, byte[] body) {
        String header(String name) {
            List<String> v = headers.get(name.toLowerCase());
            return v == null || v.isEmpty() ? null : v.get(0);
        }

        String bodyUtf8() {
            return body == null ? "" : new String(body, StandardCharsets.UTF_8);
        }
    }

    /** Scripted response + optional latency to trigger client timeouts. */
    static final class ScriptedResponse {
        final int status;
        final String contentType;
        final byte[] body;
        final long delayMillis;

        ScriptedResponse(int status, String contentType, byte[] body, long delayMillis) {
            this.status = status;
            this.contentType = contentType;
            this.body = body;
            this.delayMillis = delayMillis;
        }

        static ScriptedResponse json(int status, String json) {
            return new ScriptedResponse(status, "application/json", json.getBytes(StandardCharsets.UTF_8), 0);
        }

        static ScriptedResponse jsonDelayed(int status, String json, long delayMillis) {
            return new ScriptedResponse(status, "application/json", json.getBytes(StandardCharsets.UTF_8), delayMillis);
        }
    }

    private final HttpServer server;
    private final AtomicInteger requestCount = new AtomicInteger(0);
    private final AtomicReference<RecordedRequest> lastRequest = new AtomicReference<>();
    private volatile ScriptedResponse response = ScriptedResponse.json(200, "{}");

    GraphqlTestBackend() throws IOException {
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        this.server.createContext("/", this::handle);
        this.server.start();
    }

    private void handle(HttpExchange exchange) throws IOException {
        byte[] body;
        try (InputStream in = exchange.getRequestBody()) {
            body = in.readAllBytes();
        }
        RecordedRequest recorded = new RecordedRequest(
                exchange.getRequestMethod(),
                exchange.getRequestURI().getPath(),
                exchange.getRequestHeaders(),
                body);
        requestCount.incrementAndGet();
        lastRequest.set(recorded);

        ScriptedResponse r = response;
        if (r.delayMillis > 0) {
            try {
                Thread.sleep(r.delayMillis);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }
        exchange.getResponseHeaders().add("Content-Type", r.contentType);
        if (r.body == null || r.body.length == 0) {
            exchange.sendResponseHeaders(r.status, -1);
        } else {
            exchange.sendResponseHeaders(r.status, r.body.length);
            exchange.getResponseBody().write(r.body);
        }
        exchange.close();
    }

    void setResponse(ScriptedResponse r) {
        this.response = r;
    }

    int port() {
        return server.getAddress().getPort();
    }

    String baseUri() {
        return "http://127.0.0.1:" + port();
    }

    int requestCount() {
        return requestCount.get();
    }

    RecordedRequest lastRequest() {
        return lastRequest.get();
    }

    /** A free port that is not listening, usable to force a connection-refused (pre-execution) failure. */
    static int closedPort() throws IOException {
        HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        int p = s.getAddress().getPort();
        s.stop(0);
        return p;
    }

    void stop() {
        server.stop(0);
    }
}
