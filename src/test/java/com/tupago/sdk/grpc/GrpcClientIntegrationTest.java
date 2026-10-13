package com.tupago.sdk.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import com.tupago.sdk.core.banxico.BanxicoMessage;
import com.tupago.sdk.core.auth.CredentialProvider;
import com.tupago.sdk.core.config.TimeoutConfig;
import com.tupago.sdk.core.error.PaymentError;
import com.tupago.sdk.core.idempotency.IdempotencyKey;
import com.tupago.sdk.core.model.CatalogoInstitucion;
import com.tupago.sdk.core.model.ClientePerfil;
import com.tupago.sdk.core.model.CreateQrRequest;
import com.tupago.sdk.core.model.CreateTelefonicoRequest;
import com.tupago.sdk.core.model.GetCatalogoRequest;
import com.tupago.sdk.core.model.GetClienteRequest;
import com.tupago.sdk.core.model.GetQrStatusRequest;
import com.tupago.sdk.core.model.GetTelefonicoStatusRequest;
import com.tupago.sdk.core.result.PaymentOperationResult;
import com.tupago.sdk.core.result.PendingReconciliation;
import com.tupago.sdk.core.result.Rejected;
import com.tupago.sdk.core.result.Success;
import com.tupago.sdk.core.result.TransportFailure;
import com.tupago.sdk.core.retry.BackoffRetryPolicy;
import com.tupago.sdk.core.retry.NoRetryPolicy;
import com.tupago.sdk.grpc.v1.JsonResponse;
import com.tupago.sdk.grpc.v1.PaymentServiceGrpc;
import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import io.grpc.netty.NettyServerBuilder;
import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * End-to-end exercises of the SDK-04 core-facing gRPC client against an in-process loopback Netty server.
 * The server runs plaintext on 127.0.0.1 (ChannelSecurity allows plaintext loopback) so the full path is
 * exercised: ChannelSecurity -> BearerInterceptor -> GrpcTransport -> GrpcErrorTranslator -> core results.
 */
class GrpcClientIntegrationTest {

    private static final String CREATE_QR_JSON =
            "{\"TYP\":1,\"v\":{\"DEV\":\"D\"},\"ic\":{\"IDC\":\"IDC1\",\"SER\":3,\"ENC\":\"E\"},\"CRY\":\"C\"}";
    private static final String CREATE_TELEFONICO_JSON = "{\"detalleResultado\":\"{\\\"k\\\":\\\"v\\\"}\",\"edoPet\":1}";
    private static final String QR_STATUS_JSON = "[{\"id\":1,\"cc\":\"CC\",\"mt\":\"1.00\"}]";
    private static final String TELEFONICO_STATUS_JSON = "{\"detalleResultado\":{\"id\":1},\"edoPet\":1}";
    private static final String EXPECTED_TOKEN = "good-jwt";

    private Server server;
    private FakePaymentService fake;
    private AtomicReference<String> tokenRef;
    private GrpcTupagoClient client;

    @BeforeEach
    void setUp() throws Exception {
        fake = new FakePaymentService();
        tokenRef = new AtomicReference<>(EXPECTED_TOKEN);
        ServerInterceptor auth = new AuthInterceptor(EXPECTED_TOKEN);
        server = NettyServerBuilder.forPort(0).addService(fake).intercept(auth).build().start();
        CredentialProvider creds = () -> tokenRef.get();
        GrpcClientConfig cfg = GrpcClientConfig.builder()
                .target("127.0.0.1:" + server.getPort())
                .credentials(creds)
                .timeouts(TimeoutConfig.defaults())
                .retryPolicy(NoRetryPolicy.INSTANCE)
                .build();
        client = GrpcTupagoClient.create(cfg);
    }

    @AfterEach
    void tearDown() {
        if (client != null) {
            client.close();
        }
        if (server != null) {
            server.shutdownNow();
        }
    }

    // ---------------- AUTH ----------------

    @Test
    void missingTokenFailsClosedBeforeNetwork() {
        tokenRef.set(null);
        PaymentOperationResult<?> r = client.payments().createQr(qr("k1"));
        assertThat(r).isInstanceOf(Rejected.class);
        assertThat(((Rejected<?>) r).error()).isInstanceOf(PaymentError.AuthenticationFailure.class);
        assertThat(fake.createQrCalls.get()).isZero();
    }

    @Test
    void invalidTokenRejectedByServer() {
        tokenRef.set("wrong-jwt");
        PaymentOperationResult<?> r = client.payments().createQr(qr("k1"));
        assertThat(r).isInstanceOf(Rejected.class);
        assertThat(((Rejected<?>) r).error()).isInstanceOf(PaymentError.AuthenticationFailure.class);
    }

    @Test
    void tokenNeverLeakedIntoErrorMessages() {
        PaymentOperationResult<?> r = client.payments().createQr(qr("FORCE_INTERNAL"));
        assertThat(r).isInstanceOf(Rejected.class);
        assertThat(((Rejected<?>) r).error().message()).doesNotContain(EXPECTED_TOKEN);
    }

    // ---------------- C1 CREATE QR ----------------

    @Test
    void createQrMapsDirectMsjPagoQrObject() {
        PaymentOperationResult<BanxicoMessage> r = client.payments().createQr(qr("k1"));
        assertThat(r).isInstanceOf(Success.class);
        BanxicoMessage m = ((Success<BanxicoMessage>) r).value();
        assertThat(m.typ()).isEqualTo(1);
        assertThat(m.icIdc()).isEqualTo("IDC1");
        assertThat(m.icSer()).isEqualTo(3);
        assertThat(m.icEnc()).isEqualTo("E");
    }

    @Test
    void createQrMalformedJsonFailsClosed() {
        PaymentOperationResult<?> r = client.payments().createQr(qr("FORCE_MALFORMED"));
        assertThat(r).isInstanceOf(Rejected.class);
        assertThat(((Rejected<?>) r).error()).isInstanceOf(PaymentError.InternalFailure.class);
    }

    @Test
    void createQrPendingReconciliationExact() {
        PaymentOperationResult<?> r = client.payments().createQr(qr("FORCE_PENDING"));
        assertThat(r).isInstanceOf(PendingReconciliation.class);
        assertThat(r.retryAllowed()).isFalse();
    }

    @Test
    void createQrAmountLimitCollapsesToPaymentLimit() {
        PaymentOperationResult<?> r = client.payments().createQr(qr("FORCE_LIMIT"));
        assertThat(r).isInstanceOf(Rejected.class);
        assertThat(((Rejected<?>) r).error()).isInstanceOf(PaymentError.PaymentLimitExceeded.class);
    }

    @Test
    void createQrAlreadyExistsCollapsesToInternalFailure() {
        // gRPC does not disambiguate IdempotencyConflict vs PolicyRevisionConflict; fail closed.
        PaymentOperationResult<?> r = client.payments().createQr(qr("FORCE_CONFLICT"));
        assertThat(r).isInstanceOf(Rejected.class);
        assertThat(((Rejected<?>) r).error()).isInstanceOf(PaymentError.InternalFailure.class);
    }

    @Test
    void createQrNoRetryOnUnavailableEvenWithPolicy() {
        GrpcClientConfig cfg = GrpcClientConfig.builder()
                .target("127.0.0.1:" + server.getPort())
                .credentials(() -> EXPECTED_TOKEN)
                .retryPolicy(new BackoffRetryPolicy(3, java.time.Duration.ofMillis(1)))
                .build();
        try (GrpcTupagoClient c = GrpcTupagoClient.create(cfg)) {
            PaymentOperationResult<?> r = c.payments().createQr(qr("FORCE_UNAVAILABLE"));
            assertThat(r).isInstanceOf(TransportFailure.class);
            // Creates must NEVER blind-retry: exactly one attempt reached the server.
            assertThat(fake.createQrCalls.get()).isEqualTo(1);
        }
    }

    // ---------------- C2 CREATE TELEFONICO ----------------

    @Test
    void createTelefonicoMapsDetalleResultadoString() {
        CreateTelefonicoRequest req = CreateTelefonicoRequest.of(
                "key1", new BigDecimal("20.00"), "concepto", 2, "5512345678", 60, IdempotencyKey.of("k1"));
        PaymentOperationResult<BanxicoMessage> r = client.payments().createTelefonico(req);
        assertThat(r).isInstanceOf(Success.class);
        BanxicoMessage m = ((Success<BanxicoMessage>) r).value();
        assertThat(m.rawJson()).contains("k");
    }

    @Test
    void createTelefonicoNonNumericCelularFailsLocally() {
        CreateTelefonicoRequest req = CreateTelefonicoRequest.of(
                "key1", new BigDecimal("20.00"), "concepto", 2, "NOT-A-NUMBER", 60, IdempotencyKey.of("k1"));
        PaymentOperationResult<?> r = client.payments().createTelefonico(req);
        assertThat(r).isInstanceOf(Rejected.class);
        assertThat(((Rejected<?>) r).error()).isInstanceOf(PaymentError.ValidationFailure.class);
        assertThat(fake.createTelefonicoCalls.get()).isZero();
    }

    // ---------------- C3 QR STATUS (account-scoped) ----------------

    @Test
    void qrStatusRejectsNullIdCuentaBeforeNetwork() {
        PaymentOperationResult<?> r = client.payments().getQrStatus(GetQrStatusRequest.of("IDC1"));
        assertThat(r).isInstanceOf(Rejected.class);
        assertThat(((Rejected<?>) r).error()).isInstanceOf(PaymentError.ValidationFailure.class);
        assertThat(fake.qrStatusCalls.get()).isZero();
    }

    @Test
    void qrStatusSuccessParsesArray() {
        PaymentOperationResult<BanxicoMessage> r = client.payments().getQrStatus(GetQrStatusRequest.of("IDC1", 42L));
        assertThat(r).isInstanceOf(Success.class);
        assertThat(fake.lastQrStatusIdCuenta).isEqualTo(42L);
        BanxicoMessage m = ((Success<BanxicoMessage>) r).value();
        assertThat(m.transactions()).isNotEmpty();
    }

    @Test
    void qrStatusNotFound() {
        PaymentOperationResult<?> r = client.payments().getQrStatus(GetQrStatusRequest.of("NF", 1L));
        assertThat(r).isInstanceOf(Rejected.class);
        assertThat(((Rejected<?>) r).error()).isInstanceOf(PaymentError.NotFound.class);
    }

    @Test
    void qrStatusNoSpeculativeRetryOnUnavailable() {
        PaymentOperationResult<?> r = client.payments().getQrStatus(GetQrStatusRequest.of("UNAVAIL", 1L));
        assertThat(r).isInstanceOf(TransportFailure.class);
        // Conservative mayHaveBeenSent => no speculative retry even for reads.
        assertThat(fake.qrStatusCalls.get()).isEqualTo(1);
    }

    // ---------------- C4 TELEFONICO STATUS ----------------

    @Test
    void telefonicoStatusRejectsNullIdCuenta() {
        PaymentOperationResult<?> r = client.payments().getTelefonicoStatus(GetTelefonicoStatusRequest.of("IDC1"));
        assertThat(r).isInstanceOf(Rejected.class);
        assertThat(((Rejected<?>) r).error()).isInstanceOf(PaymentError.ValidationFailure.class);
        assertThat(fake.telefonicoStatusCalls.get()).isZero();
    }

    @Test
    void telefonicoStatusSuccess() {
        PaymentOperationResult<BanxicoMessage> r =
                client.payments().getTelefonicoStatus(GetTelefonicoStatusRequest.of("IDC1", 7L));
        assertThat(r).isInstanceOf(Success.class);
        assertThat(fake.lastTelefonicoStatusIdCuenta).isEqualTo(7L);
    }

    // ---------------- C5 CLIENTE ----------------

    @Test
    void clienteSuccessMapsTypedFields() {
        PaymentOperationResult<ClientePerfil> r = client.clientes().getCliente(GetClienteRequest.of(5L));
        assertThat(r).isInstanceOf(Success.class);
        ClientePerfil p = ((Success<ClientePerfil>) r).value();
        assertThat(p.id()).isEqualTo(5L);
        assertThat(p.nombre()).isEqualTo("Nombre");
        assertThat(p.catalogoInstitucion()).isNotNull();
        assertThat(p.catalogoInstitucion().id()).isEqualTo(9L);
    }

    @Test
    void clienteNotFound() {
        PaymentOperationResult<?> r = client.clientes().getCliente(GetClienteRequest.of(0L));
        assertThat(r).isInstanceOf(Rejected.class);
        assertThat(((Rejected<?>) r).error()).isInstanceOf(PaymentError.NotFound.class);
    }

    @Test
    void clienteInvalidDefaultShapeFailsClosed() {
        // id == 0 (proto3 default) on a returned cliente must not be coerced into a valid profile.
        PaymentOperationResult<?> r = client.clientes().getCliente(GetClienteRequest.of(-1L));
        assertThat(r).isInstanceOf(Rejected.class);
        assertThat(((Rejected<?>) r).error()).isInstanceOf(PaymentError.InternalFailure.class);
    }

    // ---------------- C6 CATALOGO ----------------

    @Test
    void catalogoSuccess() {
        PaymentOperationResult<CatalogoInstitucion> r = client.catalogos().getCatalogo(GetCatalogoRequest.of("C"));
        assertThat(r).isInstanceOf(Success.class);
        assertThat(((Success<CatalogoInstitucion>) r).value().id()).isEqualTo(9L);
    }

    @Test
    void catalogoNotFound() {
        PaymentOperationResult<?> r = client.catalogos().getCatalogo(GetCatalogoRequest.of("NF"));
        assertThat(r).isInstanceOf(Rejected.class);
        assertThat(((Rejected<?>) r).error()).isInstanceOf(PaymentError.NotFound.class);
    }

    @Test
    void catalogoInvalidClaveRejectedBeforeNetwork() {
        PaymentOperationResult<?> r = client.catalogos().getCatalogo(GetCatalogoRequest.of("bad/clave"));
        assertThat(r).isInstanceOf(Rejected.class);
        assertThat(((Rejected<?>) r).error()).isInstanceOf(PaymentError.ValidationFailure.class);
        assertThat(fake.catalogoCalls.get()).isZero();
    }

    // ---------------- TLS (plaintext law) ----------------

    @Test
    void plaintextNonLoopbackIsRefused() {
        // ChannelSecurity refuses plaintext for non-loopback targets (fail-closed).
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> com.tupago.sdk.security.ChannelSecurity.plaintextLoopback("example.com:443"))
                .isInstanceOf(RuntimeException.class);
    }

    // ---------------- helpers ----------------

    private static CreateQrRequest qr(String key) {
        return CreateQrRequest.of(1L, new BigDecimal("10.00"), "concepto", 1, "nombre", "a@b.c", 1, 60,
                IdempotencyKey.of(key));
    }

    // ---------------- fake server ----------------

    static final class FakePaymentService extends PaymentServiceGrpc.PaymentServiceImplBase {
        final AtomicInteger createQrCalls = new AtomicInteger();
        final AtomicInteger createTelefonicoCalls = new AtomicInteger();
        final AtomicInteger qrStatusCalls = new AtomicInteger();
        final AtomicInteger telefonicoStatusCalls = new AtomicInteger();
        final AtomicInteger catalogoCalls = new AtomicInteger();
        long lastQrStatusIdCuenta;
        long lastTelefonicoStatusIdCuenta;

        @Override
        public void createQr(com.tupago.sdk.grpc.v1.CreateQrRequest req, StreamObserver<JsonResponse> obs) {
            createQrCalls.incrementAndGet();
            String key = req.getIdempotencyKey();
            if ("FORCE_UNAVAILABLE".equals(key)) {
                obs.onError(Status.UNAVAILABLE.withDescription("down").asRuntimeException());
                return;
            }
            if ("FORCE_LIMIT".equals(key)) {
                obs.onError(Status.FAILED_PRECONDITION.withDescription("limit").asRuntimeException());
                return;
            }
            if ("FORCE_CONFLICT".equals(key)) {
                obs.onError(Status.ALREADY_EXISTS.withDescription("conflict").asRuntimeException());
                return;
            }
            if ("FORCE_PENDING".equals(key)) {
                obs.onError(Status.ABORTED.withDescription(
                        "TUPAGO_PENDING_RECONCILIATION; payment pending reconciliation; do not retry").asRuntimeException());
                return;
            }
            if ("FORCE_INTERNAL".equals(key)) {
                obs.onError(Status.INTERNAL.withDescription("boom").asRuntimeException());
                return;
            }
            if ("FORCE_MALFORMED".equals(key)) {
                obs.onNext(JsonResponse.newBuilder().setJson("not-json").build());
                obs.onCompleted();
                return;
            }
            obs.onNext(JsonResponse.newBuilder().setJson(CREATE_QR_JSON).build());
            obs.onCompleted();
        }

        @Override
        public void createTelefonico(com.tupago.sdk.grpc.v1.CreateTelefonicoRequest req,
                                    StreamObserver<JsonResponse> obs) {
            createTelefonicoCalls.incrementAndGet();
            obs.onNext(JsonResponse.newBuilder().setJson(CREATE_TELEFONICO_JSON).build());
            obs.onCompleted();
        }

        @Override
        public void getQrStatus(com.tupago.sdk.grpc.v1.GetStatusRequest req, StreamObserver<JsonResponse> obs) {
            qrStatusCalls.incrementAndGet();
            lastQrStatusIdCuenta = req.getIdCuenta();
            if ("NF".equals(req.getIdc())) {
                obs.onError(Status.NOT_FOUND.withDescription("no result").asRuntimeException());
                return;
            }
            if ("UNAVAIL".equals(req.getIdc())) {
                obs.onError(Status.UNAVAILABLE.withDescription("down").asRuntimeException());
                return;
            }
            obs.onNext(JsonResponse.newBuilder().setJson(QR_STATUS_JSON).build());
            obs.onCompleted();
        }

        @Override
        public void getTelefonicoStatus(com.tupago.sdk.grpc.v1.GetStatusRequest req,
                                       StreamObserver<JsonResponse> obs) {
            telefonicoStatusCalls.incrementAndGet();
            lastTelefonicoStatusIdCuenta = req.getIdCuenta();
            obs.onNext(JsonResponse.newBuilder().setJson(TELEFONICO_STATUS_JSON).build());
            obs.onCompleted();
        }

        @Override
        public void getCliente(com.tupago.sdk.grpc.v1.GetClienteRequest req,
                             StreamObserver<com.tupago.sdk.grpc.v1.ClientePerfil> obs) {
            if (req.getId() <= 0) {
                if (req.getId() < 0) {
                    // Simulate a server that returned an unset (proto3 default) id -> must fail closed client-side.
                    obs.onNext(com.tupago.sdk.grpc.v1.ClientePerfil.newBuilder()
                            .setCliente(com.tupago.sdk.grpc.v1.Cliente.newBuilder()
                                    .setId(0).setNombre("").setClaveInstitucion("").setKey("").build())
                            .build());
                    obs.onCompleted();
                    return;
                }
                obs.onError(Status.NOT_FOUND.withDescription("no cliente").asRuntimeException());
                return;
            }
            obs.onNext(com.tupago.sdk.grpc.v1.ClientePerfil.newBuilder()
                    .setCliente(com.tupago.sdk.grpc.v1.Cliente.newBuilder()
                            .setId(5).setNombre("Nombre").setClaveInstitucion("C").setKey("K").build())
                    .setCatalogoInstitucion(com.tupago.sdk.grpc.v1.CatalogoInstitucion.newBuilder()
                            .setId(9).setClaveInstitucion("C").setNombreCorto("Banco").build())
                    .build());
            obs.onCompleted();
        }

        @Override
        public void getCatalogo(com.tupago.sdk.grpc.v1.GetCatalogoRequest req,
                              StreamObserver<com.tupago.sdk.grpc.v1.CatalogoInstitucion> obs) {
            catalogoCalls.incrementAndGet();
            if ("NF".equals(req.getClaveInstitucion())) {
                obs.onError(Status.NOT_FOUND.withDescription("no catalogo").asRuntimeException());
                return;
            }
            obs.onNext(com.tupago.sdk.grpc.v1.CatalogoInstitucion.newBuilder()
                    .setId(9).setClaveInstitucion("C").setNombreCorto("Banco").build());
            obs.onCompleted();
        }
    }

    static final class AuthInterceptor implements ServerInterceptor {
        private final String expected;

        AuthInterceptor(String expected) {
            this.expected = expected;
        }

        @Override
        public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
                ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
            String auth = headers.get(Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER));
            if (auth == null || !auth.equals("Bearer " + expected)) {
                call.close(Status.UNAUTHENTICATED.withDescription("bad token"), new Metadata());
                return new ServerCall.Listener<ReqT>() {};
            }
            return next.startCall(call, headers);
        }
    }
}
