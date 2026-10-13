package com.tupago.sdk.rest;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Architecture gate (SDK-02): the REST transport MUST NOT depend on gRPC, GraphQL, Spring, Netty or other
 * HTTP frameworks, and must NOT leak {@code java.net.http} types through the public API
 * ({@code RestTupagoClient}, {@code RestClientConfig}). {@code java.net.http.HttpClient} is allowed only
 * inside the internal transport engine.
 */
class RestArchitectureTest {

    private static final List<String> FORBIDDEN = List.of(
            "io.grpc",
            "graphql",
            "org.springframework",
            "io.netty",
            "okhttp",
            "com.google.protobuf",
            "com.tupago.sdk.grpc",
            "com.tupago.sdk.client",
            "com.tupago.sdk.error",
            "com.tupago.sdk.retry",
            "com.tupago.sdk.security",
            "com.tupago.sdk.auth");

    private static final List<String> PUBLIC_API = List.of(
            "com/tupago/sdk/rest/RestTupagoClient.java",
            "com/tupago/sdk/rest/RestClientConfig.java");

    @Test
    void restMustNotDependOnForbiddenTransportFrameworks() throws IOException {
        Path root = Path.of("src", "main", "java", "com", "tupago", "sdk", "rest");
        assertThat(root).as("rest source root").exists().isDirectory();

        List<String> violations = new ArrayList<>();
        long[] counted = {0};
        try (Stream<Path> files = Files.walk(root)) {
            files.filter(p -> p.toString().endsWith(".java")).forEach(p -> {
                counted[0]++;
                try {
                    for (String line : Files.readAllLines(p)) {
                        if (!line.trim().startsWith("import ")) {
                            continue;
                        }
                        for (String forbidden : FORBIDDEN) {
                            if (line.contains(forbidden)) {
                                violations.add(p.getFileName() + " : " + line.trim() + "  [forbidden: " + forbidden + "]");
                            }
                        }
                    }
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        }
        assertThat(counted[0]).as("scanned rest source files").isGreaterThan(0);
        assertThat(violations).as("forbidden transport/framework imports in REST module").isEmpty();
    }

    @Test
    void publicApiMustNotLeakJavaNetHttp() throws IOException {
        Path root = Path.of("src", "main", "java");
        for (String apiFile : PUBLIC_API) {
            Path p = root.resolve(apiFile);
            assertThat(p).as("public API file " + apiFile).exists();
            boolean importsJavaNetHttp = Files.readAllLines(p).stream()
                    .anyMatch(l -> l.trim().startsWith("import ") && l.contains("java.net.http"));
            assertThat(importsJavaNetHttp)
                    .as("public API file " + apiFile + " must not import java.net.http")
                    .isFalse();
        }
    }

    @Test
    void restTransportUsesJdkHttpClient() throws IOException {
        Path root = Path.of("src", "main", "java", "com", "tupago", "sdk", "rest");
        boolean usesJdkClient = false;
        try (Stream<Path> files = Files.walk(root)) {
            for (Path p : (Iterable<Path>) files.filter(f -> f.toString().endsWith(".java")).toList()) {
                for (String line : Files.readAllLines(p)) {
                    if (line.contains("java.net.http.HttpClient") || line.contains("HttpResponse")
                            || line.contains("HttpRequest")) {
                        usesJdkClient = true;
                    }
                }
            }
        }
        assertThat(usesJdkClient).as("REST module must use java.net.http (not grpc/spring)").isTrue();
    }

    @Test
    void restTransportDelegatesRetryToRetryClassifier() throws IOException {
        Path transport = Path.of("src", "main", "java", "com", "tupago", "sdk", "rest", "internal", "RestTransport.java");
        assertThat(transport).as("RestTransport source").exists();
        List<String> lines = Files.readAllLines(transport);
        String source = String.join("\n", lines);

        assertThat(source).as("RestTransport must import RetryClassifier").contains("import com.tupago.sdk.core.retry.RetryClassifier;");
        assertThat(source).as("RestTransport must import RetryDecision").contains("import com.tupago.sdk.core.retry.RetryDecision;");
        assertThat(source).as("RestTransport must consult RetryClassifier.classify").contains("RetryClassifier.classify(kind, result)");
        assertThat(source).as("RestTransport must act on RetryDecision.RETRY").contains("RetryDecision.RETRY");
        assertThat(source).as("RestTransport must not embed its own create-aware retry heuristic")
                .doesNotContain("if (kind.isCreate() || te.mayHaveBeenSent())");
        assertThat(source).as("RestTransport must not carry a standalone retry-now boolean unrelated to RetryClassifier")
                .doesNotContain("retryNow =");
    }
}
