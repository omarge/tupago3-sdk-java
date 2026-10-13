package com.tupago.sdk.rest;

import com.tupago.sdk.core.auth.CredentialProvider;
import com.tupago.sdk.core.banxico.BanxicoMessage;
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
import com.tupago.sdk.core.retry.NoRetryPolicy;
import com.tupago.sdk.core.retry.RetryPolicy;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class RestClientIntegrationTest {

    private static final String TOKEN = "test-jwt-token-0123456789";

    private TestBackend backend;
    private RestTupagoClient client;

    @BeforeEach
    void setUp() throws IOException {
        backend = new TestBackend();
    }

    @AfterEach
    void tearDown() {
        if (client != null) {
            client.close();
        }
        if (backend != null) {
            backend.stop();
        }
    }

    private CredentialProvider provider() {
        return () -> TOKEN;
    }

    private RestTupagoClient client(TimeoutConfig tc, RetryPolicy rp) {
        RestClientConfig cfg = RestClientConfig.builder()
                .baseUri(backend.baseUri())
                .credentials(provider())
                .timeouts(tc == null ? TimeoutConfig.defaults() : tc)
                .retryPolicy(rp == null ? NoRetryPolicy.INSTANCE : rp)
                .build();
        return RestTupagoClient.create(cfg);
    }

    private static CreateQrRequest qr(String key) {
        return CreateQrRequest.of(1L, new BigDecimal("100.00"), "concepto", 123, "Cliente",
                "c@e.com", 1, 5, IdempotencyKey.of(key));
    }

    private static CreateTelefonicoRequest telefonico(String key) {
        return CreateTelefonicoRequest.of("CLAVE", new BigDecimal("50.00"), "concepto", 9,
                "5512345678", 5, IdempotencyKey.of(key));
    }

    // ---- request serialization + auth header ----

    @Test
    void createQrSerializesBodyAndSendsBearerToken() {
        backend.setResponse(TestBackend.ScriptedResponse.json(200,
                "{\"mensaje\":\"{\\\"TYP\\\":1}\",\"emisor\":\"E\",\"banco\":\"B\"}"));
        client = client(null, null);
        PaymentOperationResult<BanxicoMessage> r = client.payments().createQr(qr("k1"));

        assertThat(r).isInstanceOf(Success.class);
        TestBackend.RecordedRequest req = backend.lastRequest();
        assertThat(req.method()).isEqualTo("POST");
        assertThat(req.path()).isEqualTo("/api/payments/msjpagoqrusuario");
        assertThat(req.header("authorization")).isEqualTo("Bearer " + TOKEN);
        assertThat(req.header("content-type")).isEqualTo("application/json");
        assertThat(req.bodyUtf8()).contains("\"monto\":100.00").contains("\"idCuenta\":1");
    }

    @Test
    void createTelefonicoSerializesNumericCelular() {
        backend.setResponse(TestBackend.ScriptedResponse.json(200,
                "{\"detalleResultado\":\"{\\\"id\\\":\\\"X\\\"}\",\"edoPet\":0}"));
        client = client(null, null);
        client.payments().createTelefonico(telefonico("k2"));

        TestBackend.RecordedRequest req = backend.lastRequest();
        assertThat(req.bodyUtf8()).contains("\"numeroCelular\":5512345678");
        assertThat(req.path()).isEqualTo("/api/payments/msjpagotelefonicousuario");
    }

    // ---- idempotency header ----

    @Test
    void idempotencyKeySentWhenExplicit() {
        backend.setResponse(TestBackend.ScriptedResponse.json(200,
                "{\"mensaje\":\"{}\",\"emisor\":\"E\",\"banco\":\"B\"}"));
        client = client(null, null);
        client.payments().createQr(qr("my-key"));
        assertThat(backend.lastRequest().header("Idempotency-Key")).isEqualTo("my-key");
    }

    @Test
    void idempotencyKeyOmittedForLegacyUnsafe() {
        backend.setResponse(TestBackend.ScriptedResponse.json(200,
                "{\"mensaje\":\"{}\",\"emisor\":\"E\",\"banco\":\"B\"}"));
        client = client(null, null);
        CreateQrRequest legacy = CreateQrRequest.legacyUnsafe(1L, new BigDecimal("1"), "c", 1, "n", "e", 1, 5);
        assertThat(legacy.isLegacyUnsafe()).isTrue();
        client.payments().createQr(legacy);
        assertThat(backend.lastRequest().header("Idempotency-Key")).isNull();
    }

    // ---- response deserialization ----

    @Test
    void createQrSuccessWrapsRawMensaje() {
        backend.setResponse(TestBackend.ScriptedResponse.json(200,
                "{\"mensaje\":\"{\\\"TYP\\\":1,\\\"ic\\\":{\\\"IDC\\\":\\\"IDC123\\\"}}\",\"emisor\":\"E\",\"banco\":\"B\"}"));
        client = client(null, null);
        Success<BanxicoMessage> s = (Success<BanxicoMessage>) client.payments().createQr(qr("k"));
        assertThat(s.value().icIdc()).isEqualTo("IDC123");
    }

    @Test
    void createTelefonicoSuccessWrapsDetalleResultado() {
        backend.setResponse(TestBackend.ScriptedResponse.json(200,
                "{\"detalleResultado\":\"{\\\"id\\\":\\\"T1\\\",\\\"e\\\":1}\",\"edoPet\":0}"));
        client = client(null, null);
        Success<BanxicoMessage> s = (Success<BanxicoMessage>) client.payments().createTelefonico(telefonico("k"));
        assertThat(s.value().rawJson()).contains("\"id\":\"T1\"").contains("\"e\":1");
    }

    @Test
    void getQrStatusDeserializesTransactionArray() {
        backend.setResponse(TestBackend.ScriptedResponse.json(200,
                "[{\"id\":\"1\",\"e\":1,\"mt\":10.0}]"));
        client = client(null, null);
        Success<BanxicoMessage> s = (Success<BanxicoMessage>) client.payments()
                .getQrStatus(GetQrStatusRequest.of("IDC1"));
        assertThat(s.value().transactions()).hasSize(1);
        assertThat(s.value().transactions().get(0).estado()).isEqualTo(1);
        TestBackend.RecordedRequest req = backend.lastRequest();
        assertThat(req.path()).isEqualTo("/api/payments/estatusmsjqrusuario");
        assertThat(req.bodyUtf8()).contains("\"idc\":\"IDC1\"");
    }

    @Test
    void getTelefonicoStatusDeserializesDetalleResultado() {
        backend.setResponse(TestBackend.ScriptedResponse.json(200,
                "{\"detalleResultado\":{\"id\":\"T9\",\"e\":2},\"edoPet\":0}"));
        client = client(null, null);
        Success<BanxicoMessage> s = (Success<BanxicoMessage>) client.payments()
                .getTelefonicoStatus(GetTelefonicoStatusRequest.of("IDC9"));
        assertThat(s.value().transactions()).hasSize(1);
        assertThat(s.value().transactions().get(0).id()).isEqualTo("T9");
        assertThat(backend.lastRequest().bodyUtf8()).contains("\"idMsjdeCobro\":\"IDC9\"");
    }

    @Test
    void getClienteUsesOwnerScopedPath() {
        backend.setResponse(TestBackend.ScriptedResponse.json(200,
                "{\"id\":7,\"key\":\"K\",\"nombre\":\"N\",\"claveInstitucion\":\"01234\"}"));
        client = client(null, null);
        Success<ClientePerfil> s = (Success<ClientePerfil>) client.clientes().getCliente(GetClienteRequest.of(7L));
        assertThat(s.value().id()).isEqualTo(7L);
        assertThat(s.value().nombre()).isEqualTo("N");
        assertThat(s.value().claveInstitucion()).isEqualTo("01234");
        assertThat(s.value().key()).isEqualTo("K");
        assertThat(backend.lastRequest().path()).isEqualTo("/api/clientes/7");
        assertThat(backend.lastRequest().method()).isEqualTo("GET");
    }

    @Test
    void getCatalogoMapsByClave() {
        backend.setResponse(TestBackend.ScriptedResponse.json(200,
                "{\"id\":3,\"claveInstitucion\":\"01234\",\"nombreCorto\":\"BANCO X\"}"));
        client = client(null, null);
        Success<CatalogoInstitucion> s = (Success<CatalogoInstitucion>) client.catalogos()
                .getCatalogo(GetCatalogoRequest.of("01234"));
        assertThat(s.value().id()).isEqualTo(3L);
        assertThat(s.value().claveInstitucion()).isEqualTo("01234");
        assertThat(s.value().nombreCorto()).isEqualTo("BANCO X");
        assertThat(backend.lastRequest().path()).isEqualTo("/api/catalogo-institucions/clave/01234");
    }

    // ---- error mappings ----

    @Test
    void maps401ToAuthenticationFailure() {
        backend.setResponse(TestBackend.ScriptedResponse.json(401, "{\"detail\":\"no auth\"}"));
        assertRejectedError(client(null, null).payments().createQr(qr("k")), PaymentError.AuthenticationFailure.class);
    }

    @Test
    void maps403ToAuthorizationFailure() {
        backend.setResponse(TestBackend.ScriptedResponse.json(403, "{\"detail\":\"forbidden\"}"));
        assertRejectedError(client(null, null).clientes().getCliente(GetClienteRequest.of(1L)), PaymentError.AuthorizationFailure.class);
    }

    @Test
    void maps404ToNotFound() {
        backend.setResponse(TestBackend.ScriptedResponse.json(404, "{\"detail\":\"missing\"}"));
        assertRejectedError(client(null, null).clientes().getCliente(GetClienteRequest.of(99L)), PaymentError.NotFound.class);
    }

    @Test
    void maps409IdempotencyConflict() {
        backend.setResponse(TestBackend.ScriptedResponse.json(409,
                "{\"type\":\"https://www.jhipster.tech/problem/idempotency-conflict\",\"detail\":\"conflict\"}"));
        assertRejectedError(client(null, null).payments().createQr(qr("k")), PaymentError.IdempotencyConflict.class);
    }

    @Test
    void maps409PolicyRevisionConflict() {
        backend.setResponse(TestBackend.ScriptedResponse.json(409,
                "{\"type\":\"https://www.jhipster.tech/problem/payment-limit-policy-revision-conflict\",\"detail\":\"rev\"}"));
        assertRejectedError(client(null, null).payments().createQr(qr("k")), PaymentError.PolicyRevisionConflict.class);
    }

    @Test
    void maps400AmountLimitExceeded() {
        backend.setResponse(TestBackend.ScriptedResponse.json(400,
                "{\"type\":\"https://www.jhipster.tech/problem/co-di-amount-limit-exceeded\",\"detail\":\"limit\"}"));
        assertRejectedError(client(null, null).payments().createQr(qr("k")), PaymentError.PaymentLimitExceeded.class);
    }

    @Test
    void maps400PolicyDisabled() {
        backend.setResponse(TestBackend.ScriptedResponse.json(400,
                "{\"type\":\"https://www.jhipster.tech/problem/payment-limit-policy-disabled\",\"detail\":\"disabled\"}"));
        assertRejectedError(client(null, null).payments().createQr(qr("k")), PaymentError.PaymentLimitPolicyDisabled.class);
    }

    @Test
    void maps400PolicyMissing() {
        backend.setResponse(TestBackend.ScriptedResponse.json(400,
                "{\"type\":\"https://www.jhipster.tech/problem/payment-limit-policy-missing\",\"detail\":\"missing\"}"));
        assertRejectedError(client(null, null).payments().createQr(qr("k")), PaymentError.PaymentLimitPolicyMissing.class);
    }

    @Test
    void maps400ConstraintViolation() {
        backend.setResponse(TestBackend.ScriptedResponse.json(400,
                "{\"type\":\"https://www.jhipster.tech/problem/constraint-violation\",\"detail\":\"bad\"}"));
        assertRejectedError(client(null, null).payments().createQr(qr("k")), PaymentError.ValidationFailure.class);
    }

    @Test
    void maps5xxToTransportUnavailable() {
        backend.setResponse(TestBackend.ScriptedResponse.json(503, "{\"detail\":\"down\"}"));
        PaymentOperationResult<?> r = client(null, null).payments().getQrStatus(GetQrStatusRequest.of("X"));
        assertThat(r).isInstanceOf(TransportFailure.class);
        TransportFailure<?> tf = (TransportFailure<?>) r;
        assertThat(tf.error()).isInstanceOf(PaymentError.TransportUnavailable.class);
        assertThat(tf.mayHaveBeenSent()).isTrue();
    }

    // ---- PendingReconciliation (REST 202) ----

    @Test
    void createQr202MapsToPendingReconciliationNeverRetry() {
        backend.setResponse(TestBackend.ScriptedResponse.json(202,
                "{\"type\":\"https://www.jhipster.tech/problem/payment-pending-reconciliation\","
                        + "\"title\":\"pending\",\"detail\":\"reconcile\"}"));
        PaymentOperationResult<BanxicoMessage> r = client(null, null).payments().createQr(qr("k"));
        assertThat(r).isInstanceOf(PendingReconciliation.class);
        PendingReconciliation<?> p = (PendingReconciliation<?>) r;
        assertThat(p.retryAllowed()).isFalse();
        assertThat(p.code()).isEqualTo(PendingReconciliation.CODE);
    }

    // ---- create timeout -> no blind retry ----

    @Test
    void createTimeoutDoesNotBlindRetry() {
        backend.setResponse(TestBackend.ScriptedResponse.jsonDelayed(200,
                "{\"mensaje\":\"{}\",\"emisor\":\"E\",\"banco\":\"B\"}", 2000));
        TimeoutConfig tc = new TimeoutConfig(5_000L, 30_000L, 30_000L, 300L);
        client = client(tc, null);
        PaymentOperationResult<BanxicoMessage> r = client.payments().createQr(qr("k"));
        assertThat(r).isInstanceOf(TransportFailure.class);
        assertThat(backend.requestCount()).isEqualTo(1);
    }

    // ---- malformed response fail-closed ----

    @Test
    void malformedSuccessBodyFailsClosed() {
        backend.setResponse(TestBackend.ScriptedResponse.json(200, "this is not json"));
        client = client(null, null);
        PaymentOperationResult<BanxicoMessage> r = client.payments().createQr(qr("k"));
        assertThat(r).isInstanceOf(Rejected.class);
        assertThat(((Rejected<?>) r).error()).isInstanceOf(PaymentError.InternalFailure.class);
    }

    @Test
    void missingRequiredFieldFailsClosed() {
        backend.setResponse(TestBackend.ScriptedResponse.json(200, "{}"));
        client = client(null, null);
        PaymentOperationResult<BanxicoMessage> r = client.payments().createQr(qr("k"));
        assertThat(r).isInstanceOf(Rejected.class);
        assertThat(((Rejected<?>) r).error()).isInstanceOf(PaymentError.InternalFailure.class);
    }

    // ---- unknown JSON field tolerance ----

    @Test
    void toleratesUnknownFieldsInResponse() {
        backend.setResponse(TestBackend.ScriptedResponse.json(200,
                "{\"mensaje\":\"{}\",\"emisor\":\"E\",\"banco\":\"B\",\"unknownField\":\"ignored\"}"));
        client = client(null, null);
        PaymentOperationResult<BanxicoMessage> r = client.payments().createQr(qr("k"));
        assertThat(r).isInstanceOf(Success.class);
    }

    // ---- no credential leakage ----

    @Test
    void tokenIsNeverLeakedIntoErrorMessages() {
        backend.setResponse(TestBackend.ScriptedResponse.json(400,
                "{\"type\":\"https://www.jhipster.tech/problem/co-di-amount-limit-exceeded\",\"detail\":\"x\"}"));
        client = client(null, null);
        PaymentOperationResult<BanxicoMessage> r = client.payments().createQr(qr("k"));
        Rejected<?> rejected = (Rejected<?>) r;
        assertThat(rejected.error().message()).doesNotContain(TOKEN);
        // Token travels only inside the Authorization header, never in another header or the body.
        TestBackend.RecordedRequest req = backend.lastRequest();
        assertThat(req.header("authorization")).isEqualTo("Bearer " + TOKEN);
        assertThat(req.bodyUtf8()).doesNotContain(TOKEN);
        assertThat(req.header("Idempotency-Key")).isEqualTo("k");
    }

    @Test
    void missingTokenYieldsAuthenticationFailureWithoutNetworkCall() {
        RestClientConfig cfg = RestClientConfig.builder()
                .baseUri(backend.baseUri())
                .credentials(() -> null)
                .build();
        client = RestTupagoClient.create(cfg);
        PaymentOperationResult<BanxicoMessage> r = client.payments().createQr(qr("k"));
        assertThat(r).isInstanceOf(Rejected.class);
        assertThat(((Rejected<?>) r).error()).isInstanceOf(PaymentError.AuthenticationFailure.class);
        assertThat(backend.requestCount()).isEqualTo(0);
    }

    private static void assertRejectedError(PaymentOperationResult<?> r, Class<? extends PaymentError> errorType) {
        assertThat(r).isInstanceOf(Rejected.class);
        assertThat(((Rejected<?>) r).error()).isInstanceOf(errorType);
        assertThat(r.retryAllowed()).isFalse();
    }
}
