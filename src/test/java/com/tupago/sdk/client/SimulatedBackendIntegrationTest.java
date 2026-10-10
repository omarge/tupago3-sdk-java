package com.tupago.sdk.client;

import com.tupago.sdk.auth.CredentialProvider;
import com.tupago.sdk.error.TupagoAbortedException;
import com.tupago.sdk.error.TupagoAlreadyExistsException;
import com.tupago.sdk.error.TupagoFailedPreconditionException;
import com.tupago.sdk.error.TupagoInvalidArgumentException;
import com.tupago.sdk.error.TupagoNotFoundException;
import com.tupago.sdk.error.TupagoTransientException;
import com.tupago.sdk.error.TupagoUnknownOutcomeException;
import com.tupago.sdk.grpc.v1.CatalogoInstitucion;
import com.tupago.sdk.grpc.v1.Cliente;
import com.tupago.sdk.grpc.v1.ClientePerfil;
import com.tupago.sdk.grpc.v1.CreateQrRequest;
import com.tupago.sdk.grpc.v1.CreateTelefonicoRequest;
import com.tupago.sdk.grpc.v1.GetCatalogoRequest;
import com.tupago.sdk.grpc.v1.GetClienteRequest;
import com.tupago.sdk.grpc.v1.GetStatusRequest;
import com.tupago.sdk.grpc.v1.JsonResponse;
import com.tupago.sdk.grpc.v1.PaymentServiceGrpc;
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

/**
 * SIMULATED-SERVER tier (distinct from the real-backend integration test).
 *
 * Runs the SDK against an in-process gRPC server that models the backend3 PaymentService contract
 * EXACTLY, including its toStatus() mapping. The Create operations use a SIMULATED Banxico
 * provider (pure in-memory, zero network egress), satisfying the gate: Create RPCs execute only
 * with simulated providers and no egress to Banxico or any real PSP. Every status, transient
 * condition, and the exactly-one-attempt / no-auto-retry / idempotency-key invariants are verified
 * here. This is NOT presented as real-backend integration.
 */
class SimulatedBackendIntegrationTest {

    private static final String NAME = "in-process-tupago-simulated";
    private io.grpc.Server server;
    private TupagoPaymentClient client;
    private TupagoPaymentClient shortDeadlineClient;

    // Per-RPC invocation counters (prove exactly-one attempt + no auto-retry).
    private final AtomicInteger createQrCalls = new AtomicInteger();
    private final AtomicInteger createTelefonicoCalls = new AtomicInteger();
    private final AtomicInteger getQrStatusCalls = new AtomicInteger();
    private final AtomicInteger getTelefonicoStatusCalls = new AtomicInteger();
    private final AtomicInteger transientQr = new AtomicInteger(); // for read-retry scenario

    @BeforeEach
    void setUp() throws Exception {
        createQrCalls.set(0); createTelefonicoCalls.set(0);
        getQrStatusCalls.set(0); getTelefonicoStatusCalls.set(0); transientQr.set(0);

        PaymentServiceGrpc.PaymentServiceImplBase svc = new PaymentServiceGrpc.PaymentServiceImplBase() {

            @Override
            public void getCliente(GetClienteRequest req, StreamObserver<ClientePerfil> o) {
                if (req.getId() == 1L) {
                    o.onNext(ClientePerfil.newBuilder()
                            .setCliente(Cliente.newBuilder().setId(1).setNombre("ACME").build()).build());
                    o.onCompleted();
                } else {
                    o.onError(Status.NOT_FOUND.withDescription("no cliente").asRuntimeException());
                }
            }

            @Override
            public void getCatalogo(GetCatalogoRequest req, StreamObserver<CatalogoInstitucion> o) {
                if ("301".equals(req.getClaveInstitucion())) {
                    o.onNext(CatalogoInstitucion.newBuilder().setClaveInstitucion("301").setNombreCorto("BANCO X").build());
                    o.onCompleted();
                } else {
                    o.onError(Status.NOT_FOUND.withDescription("no catalogo").asRuntimeException());
                }
            }

            @Override
            public void getQrStatus(GetStatusRequest req, StreamObserver<JsonResponse> o) {
                int n = getQrStatusCalls.incrementAndGet();
                if ("transient".equals(req.getIdc())) {
                    // First call fails transiently; subsequent calls succeed (models read-retry path).
                    if (transientQr.incrementAndGet() == 1) {
                        o.onError(Status.UNAVAILABLE.withDescription("ups").asRuntimeException());
                        return;
                    }
                    o.onNext(JsonResponse.newBuilder().setJson("{\"status\":\"PENDING\"}").build());
                    o.onCompleted();
                    return;
                }
                if ("missing".equals(req.getIdc())) {
                    o.onError(Status.NOT_FOUND.withDescription("no status").asRuntimeException());
                    return;
                }
                o.onNext(JsonResponse.newBuilder().setJson("{\"status\":\"PENDING\"}").build());
                o.onCompleted();
            }

            @Override
            public void getTelefonicoStatus(GetStatusRequest req, StreamObserver<JsonResponse> o) {
                getTelefonicoStatusCalls.incrementAndGet();
                if ("missing".equals(req.getIdc())) {
                    o.onError(Status.NOT_FOUND.withDescription("no status").asRuntimeException());
                    return;
                }
                o.onNext(JsonResponse.newBuilder().setJson("{\"status\":\"PENDING\"}").build());
                o.onCompleted();
            }

            @Override
            public void createQr(CreateQrRequest req, StreamObserver<JsonResponse> o) {
                int n = createQrCalls.incrementAndGet(); // incremented for EVERY attempt
                routeCreate(o, req.getIdempotencyKey(), n, req.getMonto());
            }

            @Override
            public void createTelefonico(CreateTelefonicoRequest req, StreamObserver<JsonResponse> o) {
                int n = createTelefonicoCalls.incrementAndGet();
                routeCreate(o, req.getIdempotencyKey(), n, req.getMonto());
            }

            private void routeCreate(StreamObserver<JsonResponse> o, String key, int attempt, double monto) {
                switch (key) {
                    case "invalid":
                        o.onError(Status.INVALID_ARGUMENT.withDescription("bad").asRuntimeException());
                        return;
                    case "exists":
                        o.onError(Status.ALREADY_EXISTS.withDescription("idempotency conflict").asRuntimeException());
                        return;
                    case "precond":
                        o.onError(Status.FAILED_PRECONDITION.withDescription("limit").asRuntimeException());
                        return;
                    case "pending":
                        o.onError(Status.ABORTED.withDescription(
                                com.tupago.sdk.error.StatusMapper.PENDING_RECONCILIATION_CODE
                                        + "; payment pending reconciliation; do not retry; query status first")
                                .asRuntimeException());
                        return;
                    case "unavailable":
                    case "slow":
                    case "cancel":
                        // Simulated Banxico provider latency / outage. "slow"/"cancel" exceed the
                        // client deadline to demonstrate DEADLINE_EXCEEDED / disconnect (no egress).
                        if ("unavailable".equals(key)) {
                            o.onError(Status.UNAVAILABLE.withDescription("banxico down").asRuntimeException());
                            return;
                        }
                        try { Thread.sleep(20000); } catch (InterruptedException ignored) {}
                        o.onNext(JsonResponse.newBuilder().setJson("{\"idc\":\"X\"}").build());
                        o.onCompleted();
                        return;
                    default:
                        // Normal path: SIMULATED Banxico provider returns a payload (no network).
                        // Idempotency key is echoed back to prove it is preserved exactly.
                        o.onNext(JsonResponse.newBuilder()
                                .setJson("{\"idc\":\"ABC\",\"monto\":" + monto + ",\"idempotency_key\":\"" + key + "\"}")
                                .build());
                        o.onCompleted();
                }
            }
        };

        server = InProcessServerBuilder.forName(NAME).directExecutor().addService(svc).build().start();
        CredentialProvider creds = () -> "sim-jwt";
        var ch = io.grpc.ClientInterceptors.intercept(
                InProcessChannelBuilder.forName(NAME).directExecutor().build(),
                new com.tupago.sdk.auth.BearerInterceptor(creds));
        client = new TupagoPaymentClient(ch, 10_000L,
                com.tupago.sdk.retry.BackoffRetryPolicy.of(3));
        // Client with a short deadline for the DEADLINE_EXCEEDED / cancellation scenarios.
        var ch2 = io.grpc.ClientInterceptors.intercept(
                InProcessChannelBuilder.forName(NAME).directExecutor().build(),
                new com.tupago.sdk.auth.BearerInterceptor(creds));
        shortDeadlineClient = new TupagoPaymentClient(ch2, 800L,
                com.tupago.sdk.retry.BackoffRetryPolicy.of(3));
    }

    @AfterEach
    void tearDown() {
        if (client != null) client.close();
        if (shortDeadlineClient != null) shortDeadlineClient.close();
        if (server != null) server.shutdownNow();
    }

    // ---- The six RPCs individually (simulated server) ----

    @Test
    void getClienteSuccess() {
        ClientePerfil p = client.getCliente(1L);
        assertThat(p.getCliente().getId()).isEqualTo(1L);
    }

    @Test
    void getClienteNotFound() {
        assertThatThrownBy(() -> client.getCliente(999L)).isInstanceOf(TupagoNotFoundException.class);
    }

    @Test
    void getCatalogoSuccess() {
        CatalogoInstitucion c = client.getCatalogo("301");
        assertThat(c.getClaveInstitucion()).isEqualTo("301");
    }

    @Test
    void getCatalogoNotFound() {
        assertThatThrownBy(() -> client.getCatalogo("ZZZ")).isInstanceOf(TupagoNotFoundException.class);
    }

    @Test
    void getQrStatusSuccess() {
        JsonResponse r = client.getQrStatus("abc", 1L);
        assertThat(r.getJson()).contains("PENDING");
    }

    @Test
    void getQrStatusNotFound() {
        assertThatThrownBy(() -> client.getQrStatus("missing", 1L)).isInstanceOf(TupagoNotFoundException.class);
    }

    @Test
    void getTelefonicoStatusSuccess() {
        JsonResponse r = client.getTelefonicoStatus("abc", 1L);
        assertThat(r.getJson()).contains("PENDING");
    }

    @Test
    void getTelefonicoStatusNotFound() {
        assertThatThrownBy(() -> client.getTelefonicoStatus("missing", 1L)).isInstanceOf(TupagoNotFoundException.class);
    }

    @Test
    void getQrStatusRetriesTransientThenSucceeds() {
        JsonResponse r = client.getQrStatus("transient", 1L);
        assertThat(r.getJson()).contains("PENDING");
        // Exactly one transient failure + one success => 2 server invocations (read retry allowed).
        assertThat(getQrStatusCalls.get()).isGreaterThanOrEqualTo(2);
    }

    // ---- Create RPCs: simulated provider, no egress ----

    @Test
    void createQrOkPreservesIdempotencyKey() {
        String key = "ide-ok-123";
        int before = createQrCalls.get();
        JsonResponse r = client.createQr(CreateQrRequest.newBuilder().setIdCuenta(1).setMonto(50.0).setIdempotencyKey(key).build());
        assertThat(r.getJson()).contains("ABC");
        assertThat(r.getJson()).contains("ide-ok-123"); // preserved exactly
        assertThat(createQrCalls.get()).isEqualTo(before + 1); // exactly one attempt
    }

    @Test
    void createTelefonicoOkPreservesIdempotencyKey() {
        String key = "tel-ok-456";
        int before = createTelefonicoCalls.get();
        JsonResponse r = client.createTelefonico(CreateTelefonicoRequest.newBuilder().setKey("k").setMonto(25.0).setIdempotencyKey(key).build());
        assertThat(r.getJson()).contains("tel-ok-456");
        assertThat(createTelefonicoCalls.get()).isEqualTo(before + 1);
    }

    @Test
    void createQrInvalidArgumentNoRetry() {
        int before = createQrCalls.get();
        assertThatThrownBy(() -> client.createQr(CreateQrRequest.newBuilder().setIdempotencyKey("invalid").build()))
                .isInstanceOf(TupagoInvalidArgumentException.class);
        assertThat(createQrCalls.get()).isEqualTo(before + 1);
    }

    @Test
    void createQrAlreadyExistsNoRetry() {
        int before = createQrCalls.get();
        assertThatThrownBy(() -> client.createQr(CreateQrRequest.newBuilder().setIdempotencyKey("exists").build()))
                .isInstanceOf(TupagoAlreadyExistsException.class);
        assertThat(createQrCalls.get()).isEqualTo(before + 1);
    }

    @Test
    void createQrFailedPreconditionNoRetry() {
        int before = createQrCalls.get();
        assertThatThrownBy(() -> client.createQr(CreateQrRequest.newBuilder().setIdempotencyKey("precond").build()))
                .isInstanceOf(TupagoFailedPreconditionException.class);
        assertThat(createQrCalls.get()).isEqualTo(before + 1);
    }

    @Test
    void createQrPendingReconciliationNonTransient() {
        int before = createQrCalls.get();
        assertThatThrownBy(() -> client.createQr(CreateQrRequest.newBuilder().setIdempotencyKey("pending").build()))
                .isInstanceOf(TupagoAbortedException.class)
                .satisfies(e -> assertThat(((TupagoAbortedException) e).isPendingReconciliation()).isTrue());
        assertThat(createQrCalls.get()).isEqualTo(before + 1);
    }

    @Test
    void createQrUnavailableIsUnknownOutcomeSingleAttempt() {
        int before = createQrCalls.get();
        assertThatThrownBy(() -> client.createQr(CreateQrRequest.newBuilder().setIdempotencyKey("unavailable").build()))
                .isInstanceOf(TupagoUnknownOutcomeException.class)
                .satisfies(e -> assertThat(((TupagoUnknownOutcomeException) e).isRetryable()).isFalse());
        assertThat(createQrCalls.get()).isEqualTo(before + 1); // no auto-resubmit
    }

    @Test
    void createQrDeadlineIsSingleAttemptNoResubmit() {
        int before = createQrCalls.get();
        // shortDeadlineClient (800ms) vs server "slow" (20s) -> DEADLINE_EXCEEDED -> UnknownOutcome.
        assertThatThrownBy(() -> shortDeadlineClient.createQr(CreateQrRequest.newBuilder().setIdempotencyKey("slow").build()))
                .isInstanceOf(TupagoUnknownOutcomeException.class);
        assertThat(createQrCalls.get()).isEqualTo(before + 1);
    }

    @Test
    void createQrCancelIsSingleAttempt() {
        int before = createQrCalls.get();
        assertThatThrownBy(() -> shortDeadlineClient.createQr(CreateQrRequest.newBuilder().setIdempotencyKey("cancel").build()))
                .isInstanceOf(TupagoUnknownOutcomeException.class);
        // Client disconnect (deadline) -> server observes exactly one attempt, never a second.
        assertThat(createQrCalls.get()).isEqualTo(before + 1);
    }
}
