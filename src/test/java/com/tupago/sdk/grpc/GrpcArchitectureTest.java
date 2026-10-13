package com.tupago.sdk.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import com.tupago.sdk.core.client.CatalogoOperations;
import com.tupago.sdk.core.client.ClienteOperations;
import com.tupago.sdk.core.client.PaymentOperations;
import com.tupago.sdk.core.client.TupagoClient;
import com.tupago.sdk.grpc.internal.GrpcTransport;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * SDK-04 architecture gates (no ArchUnit dependency): verifies the new gRPC adapter is reconciled with
 * the transport-neutral core and does not duplicate financial-safety authorities.
 */
class GrpcArchitectureTest {

    @Test
    void grpcClientImplementsCoreContract() {
        assertThat(GrpcTupagoClient.class).isAssignableTo(TupagoClient.class);
    }

    @Test
    void publicCapabilitiesReturnCoreInterfacesNotProto() throws Exception {
        // payments()/clientes()/catalogos() must surface only core types (no generated protobuf leakage).
        assertThat(GrpcTupagoClient.class.getMethod("payments").getReturnType()).isEqualTo(PaymentOperations.class);
        assertThat(GrpcTupagoClient.class.getMethod("clientes").getReturnType()).isEqualTo(ClienteOperations.class);
        assertThat(GrpcTupagoClient.class.getMethod("catalogos").getReturnType()).isEqualTo(CatalogoOperations.class);
    }

    @Test
    void transportAndTranslatorResideInInternalPackage() {
        assertThat(GrpcTransport.class.getPackageName()).isEqualTo("com.tupago.sdk.grpc.internal");
    }

    @Test
    void transportDefersRetryAuthorityToCoreRetryClassifier() throws IOException {
        String src = readSource("com/tupago/sdk/grpc/internal/GrpcTransport.java");
        assertThat(src).contains("import com.tupago.sdk.core.retry.RetryClassifier;");
        // The single semantic retry authority is invoked by name; no legacy retry heuristic in the new path.
        assertThat(src).contains("RetryClassifier.classify");
        // Channel security is the sole channel builder entry point (fail-closed TLS/plaintext law).
        assertThat(src).contains("ChannelSecurity.forTarget");
    }

    @Test
    void configDoesNotExposeProtoTypes() throws IOException {
        String src = readSource("com/tupago/sdk/grpc/GrpcClientConfig.java");
        assertThat(src).doesNotContain("grpc.v1");
    }

    private static String readSource(String relative) throws IOException {
        // Test process working directory is the module root for Gradle `test`.
        Path p = Path.of("src/main/java/" + relative);
        List<String> lines = Files.readAllLines(p);
        return String.join("\n", lines);
    }
}
