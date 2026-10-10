package com.tupago.sdk.client;

import com.google.protobuf.Descriptors;
import com.tupago.sdk.grpc.v1.TupagoPaymentProto;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Certifies wire-level equivalence with backend3 payment.proto (correction #4): only the
 * java_package option differs for SDK generation; the protobuf package, service, RPC names,
 * field numbers and types are preserved exactly.
 */
class WireEquivalenceTest {

    @Test
    void protoPackageAndServiceMatchBackend() {
        Descriptors.FileDescriptor fd = TupagoPaymentProto.getDescriptor();
        assertThat(fd.getPackage()).isEqualTo("tupago.api.v1");

        assertThat(fd.getServices()).hasSize(1);
        Descriptors.ServiceDescriptor svc = fd.getServices().get(0);
        assertThat(svc.getName()).isEqualTo("PaymentService");

        List<String> rpcs = svc.getMethods().stream().map(Descriptors.MethodDescriptor::getName).toList();
        assertThat(rpcs).containsExactlyInAnyOrder(
                "GetCliente", "GetCatalogo", "GetQrStatus", "GetTelefonicoStatus",
                "CreateQr", "CreateTelefonico");
    }

    @Test
    void createQrFieldsPreserveNumbersAndTypes() {
        Descriptors.Descriptor d = com.tupago.sdk.grpc.v1.CreateQrRequest.getDescriptor();
        assertThat(d.findFieldByName("id_cuenta").getNumber()).isEqualTo(1);
        assertThat(d.findFieldByName("monto").getNumber()).isEqualTo(2);
        assertThat(d.findFieldByName("idempotency_key").getNumber()).isEqualTo(9);
        assertThat(d.findFieldByName("monto").getJavaType()).isEqualTo(Descriptors.FieldDescriptor.JavaType.DOUBLE);
    }
}
