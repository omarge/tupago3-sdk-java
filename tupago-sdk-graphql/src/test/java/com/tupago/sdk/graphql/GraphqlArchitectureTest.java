package com.tupago.sdk.graphql;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Architecture gate (SDK-03): the GraphQL transport MUST NOT depend on gRPC, Spring, Netty, protobuf, or
 * the REST module; it MUST depend only on core; it MUST delegate retry decisions to {@code RetryClassifier};
 * and operations MUST issue queries via the constant documents + {@code variables} (never by concatenating
 * caller input into the GraphQL source string). {@code java.net.http} is permitted only inside the internal
 * transport engine, never in the public API.
 */
class GraphqlArchitectureTest {

    private static final List<String> FORBIDDEN = List.of(
            "io.grpc",
            "org.springframework",
            "io.netty",
            "okhttp",
            "com.google.protobuf",
            "com.tupago.sdk.rest",
            "com.tupago.sdk.grpc",
            "com.tupago.sdk.client",
            "com.tupago.sdk.error",
            "com.tupago.sdk.retry",
            "com.tupago.sdk.security",
            "com.tupago.sdk.auth");

    private static final List<String> PUBLIC_API = List.of(
            "com/tupago/sdk/graphql/GraphqlTupagoClient.java",
            "com/tupago/sdk/graphql/GraphqlClientConfig.java");

    @Test
    void noForbiddenTransportDependencies() throws IOException {
        Path root = Path.of("src", "main", "java", "com", "tupago", "sdk", "graphql");
        assertThat(root).as("graphql source root").exists().isDirectory();

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
        assertThat(counted[0]).as("scanned graphql source files").isGreaterThan(0);
        assertThat(violations).as("forbidden transport/framework imports in GraphQL module").isEmpty();
    }

    @Test
    void publicApiDoesNotLeakJavaNetHttp() throws IOException {
        Path root = Path.of("src", "main", "java");
        for (String apiFile : PUBLIC_API) {
            Path p = root.resolve(apiFile);
            assertThat(p).as("public API file " + apiFile).exists();
            boolean importsJavaNetHttp = Files.readAllLines(p).stream()
                    .anyMatch(l -> l.trim().startsWith("import ") && l.contains("java.net.http"));
            assertThat(importsJavaNetHttp)
                    .as("public API file " + apiFile + " must not import java.net.http").isFalse();
        }
    }

    @Test
    void graphqlTransportDelegatesRetryToRetryClassifier() throws IOException {
        Path transport = Path.of("src", "main", "java", "com", "tupago", "sdk", "graphql", "internal", "GraphqlTransport.java");
        assertThat(transport).as("GraphqlTransport source").exists();
        List<String> lines = Files.readAllLines(transport);
        String source = String.join("\n", lines);

        assertThat(source).as("GraphqlTransport must import RetryClassifier")
                .contains("import com.tupago.sdk.core.retry.RetryClassifier;");
        assertThat(source).as("GraphqlTransport must import RetryDecision")
                .contains("import com.tupago.sdk.core.retry.RetryDecision;");
        assertThat(source).as("GraphqlTransport must consult RetryClassifier.classify")
                .contains("RetryClassifier.classify(kind, result)");
        assertThat(source).as("GraphqlTransport must act on RetryDecision.RETRY")
                .contains("RetryDecision.RETRY");
        assertThat(source).as("GraphqlTransport must not embed its own create-aware retry heuristic")
                .doesNotContain("if (kind.isCreate() || te.mayHaveBeenSent())");
        assertThat(source).as("GraphqlTransport must not carry a standalone retry-now boolean unrelated to RetryClassifier")
                .doesNotContain("retryNow =");
    }

    @Test
    void operationsUseConstantQueriesWithVariables() throws IOException {
        Path queryFile = Path.of("src", "main", "java", "com", "tupago", "sdk", "graphql", "internal", "GraphqlQuery.java");
        String querySource = String.join("\n", Files.readAllLines(queryFile));
        // Variables are referenced by placeholder; caller data never concatenated into the source.
        assertThat(querySource).contains("$input").contains("$idempotencyKey")
                .contains("$idc").contains("$id").contains("$claveInstitucion");

        Path transport = Path.of("src", "main", "java", "com", "tupago", "sdk", "graphql", "internal", "GraphqlTransport.java");
        String transportSource = String.join("\n", Files.readAllLines(transport));
        // The envelope carries the operation through `variables`, not interpolated into `query`.
        assertThat(transportSource).contains("\"variables\"").contains("\"operationName\"");

        // Operations reference the constant query documents (not inline string-built queries).
        for (String opFile : List.of(
                "com/tupago/sdk/graphql/GraphqlPaymentOperations.java",
                "com/tupago/sdk/graphql/GraphqlClienteOperations.java",
                "com/tupago/sdk/graphql/GraphqlCatalogoOperations.java")) {
            String src = String.join("\n", Files.readAllLines(Path.of("src", "main", "java", opFile)));
            assertThat(src).as(opFile + " must use GraphqlQuery constants")
                    .contains("GraphqlQuery.");
        }
    }
}
