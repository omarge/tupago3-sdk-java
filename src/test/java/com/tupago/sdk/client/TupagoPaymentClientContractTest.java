package com.tupago.sdk.client;

import com.tupago.sdk.auth.CredentialProvider;
import com.tupago.sdk.error.TupagoAbortedException;
import com.tupago.sdk.error.TupagoAlreadyExistsException;
import com.tupago.sdk.error.TupagoUnauthenticatedException;
import com.tupago.sdk.grpc.v1.CatalogoInstitucion;
import com.tupago.sdk.grpc.v1.Cliente;
import com.tupago.sdk.grpc.v1.ClientePerfil;
import com.tupago.sdk.grpc.v1.CreateQrRequest;
import com.tupago.sdk.grpc.v1.JsonResponse;
import com.tupago.sdk.grpc.v1.PaymentServiceGrpc;
import com.tupago.sdk.retry.BackoffRetryPolicy;
import com.tupago.sdk.retry.NoRetryPolicy;
import io.grpc.Status;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TupagoPaymentClientContractTest {

    private static final String NAME = "in-process-tupago-test";
    private io.grpc.Server server;
    private TupagoPaymentClient client;
    private final AtomicInteger createQrCalls = new AtomicInteger();

    @BeforeEach
    void setUp() throws Exception {
        PaymentServiceGrpc.PaymentServiceImplBase service = new PaymentServiceGrpc.PaymentServiceImplBase() {
            @Override
            public void getCliente(com.tupago.sdk.grpc.v1.GetClienteRequest request, StreamObserver<ClientePerfil> obs) {
                obs.onNext(ClientePerfil.newBuilder()
                        .setCliente(Cliente.newBuilder().setId(request.getId()).setNombre("ACME").build())
                        .build());
                obs.onCompleted();
            }

            @Override
            public void getCatalogo(com.tupago.sdk.grpc.v1.GetCatalogoRequest request, StreamObserver<CatalogoInstitucion> obs) {
                obs.onNext(CatalogoInstitucion.newBuilder()
                        .setClaveInstitucion(request.getClaveInstitucion()).setNombreCorto("BANCO X").build());
                obs.onCompleted();
            }

            @Override
            public void createQr(CreateQrRequest request, StreamObserver<JsonResponse> obs) {
                createQrCalls.incrementAndGet();
                if (request.getIdempotencyKey().equals("dup")) {
                    obs.onError(Status.ALREADY_EXISTS.withDescription("idempotency conflict").asRuntimeException());
                    return;
                }
                if (request.getIdempotencyKey().equals("pending")) {
                    obs.onError(Status.ABORTED.withDescription(
                            "TUPAGO_PENDING_RECONCILIATION; payment pending reconciliation; do not retry; query status first")
                            .asRuntimeException());
                    return;
                }
                obs.onNext(JsonResponse.newBuilder().setJson("{\"idc\":\"ABC\",\"monto\":" + request.getMonto() + "}").build());
                obs.onCompleted();
            }

            @Override
            public void getQrStatus(com.tupago.sdk.grpc.v1.GetStatusRequest request, StreamObserver<JsonResponse> obs) {
                obs.onNext(JsonResponse.newBuilder().setJson("{\"status\":\"PENDING\"}").build());
                obs.onCompleted();
            }
        };

        server = InProcessServerBuilder.forName(NAME).directExecutor().addService(service).build().start();

        CredentialProvider creds = () -> "test-jwt-token";
        io.grpc.Channel channel = io.grpc.ClientInterceptors.intercept(
                InProcessChannelBuilder.forName(NAME).directExecutor().build(),
                new com.tupago.sdk.auth.BearerInterceptor(creds));
        client = new TupagoPaymentClient(channel, 5_000L, BackoffRetryPolicy.of(3));
    }

    @AfterEach
    void tearDown() {
        if (client != null) client.close();
        if (server != null) server.shutdownNow();
    }

    @Test
    void getClienteReturnsProfile() {
        ClientePerfil p = client.getCliente(42L);
        assertThat(p.getCliente().getId()).isEqualTo(42L);
        assertThat(p.getCliente().getNombre()).isEqualTo("ACME");
    }

    @Test
    void getCatalogoReturnsInstitution() {
        CatalogoInstitucion c = client.getCatalogo("BANCOX");
        assertThat(c.getClaveInstitucion()).isEqualTo("BANCOX");
        assertThat(c.getNombreCorto()).isEqualTo("BANCO X");
    }

    @Test
    void createQrReturnsJsonResponse() {
        CreateQrRequest req = CreateQrRequest.newBuilder()
                .setIdCuenta(1).setMonto(100.0).setIdempotencyKey("k1").build();
        JsonResponse r = client.createQr(req);
        assertThat(r.getJson()).contains("ABC");
        assertThat(createQrCalls.get()).isEqualTo(1);
    }

    @Test
    void createQrAlreadyExistsIsNotRetried() {
        CreateQrRequest req = CreateQrRequest.newBuilder().setIdempotencyKey("dup").build();
        assertThatThrownBy(() -> client.createQr(req))
                .isInstanceOf(TupagoAlreadyExistsException.class);
        // NoRetryPolicy: exactly one server invocation despite the error.
        assertThat(createQrCalls.get()).isEqualTo(1);
    }

    @Test
    void createQrPendingReconciliationIsNonTransient() {
        CreateQrRequest req = CreateQrRequest.newBuilder().setIdempotencyKey("pending").build();
        assertThatThrownBy(() -> client.createQr(req))
                .isInstanceOf(TupagoAbortedException.class)
                .satisfies(e -> assertThat(((TupagoAbortedException) e).isPendingReconciliation()).isTrue());
        assertThat(createQrCalls.get()).isEqualTo(1);
    }

    @Test
    void missingBearerFailsClosed() {
        io.grpc.Channel badChannel = io.grpc.ClientInterceptors.intercept(
                InProcessChannelBuilder.forName(NAME).directExecutor().build(),
                new com.tupago.sdk.auth.BearerInterceptor(() -> null));
        TupagoPaymentClient badClient = new TupagoPaymentClient(badChannel, 5_000L, NoRetryPolicy.INSTANCE);
        try {
            assertThatThrownBy(() -> badClient.getCatalogo("X"))
                    .isInstanceOf(TupagoUnauthenticatedException.class);
        } finally {
            badClient.close();
        }
    }
}
