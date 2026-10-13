package com.tupago.sdk.core.arch;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Architecture gate (SDK-01, section 20): the transport-neutral core MUST NOT depend on any transport
 * or framework-specific type. It may only depend on Jackson (for the Banxico payload model).
 */
class CoreArchitectureTest {

    private static final List<String> FORBIDDEN = List.of(
            "io.grpc",                 // gRPC Java
            "graphql",                 // GraphQL Java
            "org.springframework",      // Spring (WebFlux/Context)
            "io.netty",                // Netty
            "okhttp",                  // HTTP client framework
            "com.google.protobuf",     // generated protobuf
            "com.tupago.sdk.grpc",     // generated gRPC stubs (transport)
            "com.tupago.sdk.client",   // gRPC TupagoPaymentClient (transport)
            "com.tupago.sdk.error",    // gRPC-flavored error taxonomy (transport)
            "com.tupago.sdk.retry",    // gRPC-flavored retry (transport)
            "com.tupago.sdk.security", // gRPC channel/TLS (transport)
            "com.tupago.sdk.auth"      // gRPC BearerInterceptor (transport)
    );

    @Test
    void coreMustNotDependOnForbiddenTransportFrameworks() throws IOException {
        Path root = Path.of("src", "main", "java", "com", "tupago", "sdk", "core");
        assertThat(root).as("core source root").exists().isDirectory();

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
        assertThat(counted[0]).as("scanned core source files").isGreaterThan(0);
        assertThat(violations).as("forbidden transport/framework imports in core").isEmpty();
    }
}
