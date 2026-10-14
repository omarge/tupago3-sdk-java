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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    private static void assertAmbiguousCreate(PaymentOperationResult<?> r) {
        assertThat(r).isInstanceOf(TransportFailure.class);
        TransportFailure<?> tf = (TransportFailure<?>) r;
        assertThat(tf.mayHaveBeenSent()).isTrue();
        assertThat(tf.retryAllowed()).isFalse();
    }

    private static final String CREATE_QR_OBJECT = "{"
            + "\"mensaje\":{"
            + "  \"TYP\":1,"
            + "  \"v\":{\"DEV\":\"DEVICE\"},"
            + "  \"ic\":{\"IDC\":\"IDC123\",\"SER\":7,\"ENC\":\"ENCVALUE\"},"
            + "  \"CRY\":\"CRYPTOVALUE\","
            + "  \"futureField\":{\"x\":1}"
            + "},"
            + "\"emisor\":\"EMISOR\",\"banco\":\"BANCO\"}";

    // ---- A. CreateQr ----

    @Test
    void createQrMensajeObjectSuccess() {
        backend.setResponse(TestBackend.ScriptedResponse.json(200, CREATE_QR_OBJECT));
        client = client(null, null);
        Success<BanxicoMessage> s = (Success<BanxicoMessage>) client.payments().createQr(qr("k"));
        BanxicoMessage m = s.value();
        assertThat(m.typ()).isEqualTo(1);
        assertThat(m.deviceDev()).isEqualTo("DEVICE");
        assertThat(m.icIdc()).isEqualTo("IDC123");
        assertThat(m.icSer()).isEqualTo(7); // backend SER is Integer (B5)
        assertThat(m.icEnc()).isEqualTo("ENCVALUE");
        assertThat(m.rawJson()).contains("CRY");
        assertThat(m.rawJson()).contains("futureField");
        assertThat(m.raw().path("futureField").path("x").asInt()).isEqualTo(1);
        TestBackend.RecordedRequest req = backend.lastRequest();
        assertThat(req.path()).isEqualTo("/api/payments/msjpagoqrusuario");
        assertThat(req.header("authorization")).isEqualTo("Bearer " + TOKEN);
        assertThat(req.bodyUtf8()).contains("\"monto\":100.00").contains("\"idCuenta\":1");
    }

    @Test
    void createQrMensajeStringIsRejected() {
        backend.setResponse(TestBackend.ScriptedResponse.json(200,
                "{\"mensaje\":\"{\\\"TYP\\\":1}\",\"emisor\":\"E\",\"banco\":\"B\"}"));
        client = client(null, null);
        PaymentOperationResult<BanxicoMessage> r = client.payments().createQr(qr("k"));
        assertAmbiguousCreate(r);
    }

    @Test
    void createQrMensajeAbsentFailsClosed() {
        backend.setResponse(TestBackend.ScriptedResponse.json(200, "{\"emisor\":\"E\",\"banco\":\"B\"}"));
        client = client(null, null);
        assertAmbiguousCreate(client.payments().createQr(qr("k")));
    }

    @Test
    void createQrMensajeNullFailsClosed() {
        backend.setResponse(TestBackend.ScriptedResponse.json(200, "{\"mensaje\":null,\"emisor\":\"E\"}"));
        client = client(null, null);
        assertAmbiguousCreate(client.payments().createQr(qr("k")));
    }

    @Test
    void createQrMalformedTopLevelJsonFailsClosed() {
        backend.setResponse(TestBackend.ScriptedResponse.json(200, "this is not json"));
        client = client(null, null);
        assertAmbiguousCreate(client.payments().createQr(qr("k")));
    }

    // ---- B. CreateTelefonico ----

    @Test
    void createTelefonicoTextualDetalleSuccess() {
        backend.setResponse(TestBackend.ScriptedResponse.json(200,
                "{\"detalleResultado\":\"{\\\"id\\\":\\\"T1\\\",\\\"e\\\":1}\",\"edoPet\":0}"));
        client = client(null, null);
        Success<BanxicoMessage> s = (Success<BanxicoMessage>) client.payments().createTelefonico(telefonico("k"));
        assertThat(s.value().rawJson()).contains("\"id\":\"T1\"").contains("\"e\":1");
        assertThat(backend.lastRequest().path()).isEqualTo("/api/payments/msjpagotelefonicousuario");
        assertThat(backend.lastRequest().bodyUtf8()).contains("\"numeroCelular\":5512345678");
    }

    @Test
    void createTelefonicoNonTextualDetalleFailsClosed() {
        backend.setResponse(TestBackend.ScriptedResponse.json(200,
                "{\"detalleResultado\":{\"id\":\"X\"},\"edoPet\":0}"));
        client = client(null, null);
        assertAmbiguousCreate(client.payments().createTelefonico(telefonico("k")));
    }

    @Test
    void createTelefonicoMalformedEmbeddedJsonFailsClosed() {
        backend.setResponse(TestBackend.ScriptedResponse.json(200,
                "{\"detalleResultado\":\"{not json\",\"edoPet\":0}"));
        client = client(null, null);
        assertAmbiguousCreate(client.payments().createTelefonico(telefonico("k")));
    }

    // ---- C. QR Status ----

    @Test
    void getQrStatusArraySuccess() {
        backend.setResponse(TestBackend.ScriptedResponse.json(200,
                "[{\"id\":\"1\",\"e\":1,\"mt\":10.0}]"));
        client = client(null, null);
        Success<BanxicoMessage> s = (Success<BanxicoMessage>) client.payments()
                .getQrStatus(GetQrStatusRequest.of("IDC1"));
        assertThat(s.value().transactions()).hasSize(1);
        assertThat(s.value().transactions().get(0).estado()).isEqualTo(1);
        assertThat(backend.lastRequest().bodyUtf8()).contains("\"idc\":\"IDC1\"");
    }

    @Test
    void getQrStatusMalformedJsonFailsClosed() {
        backend.setResponse(TestBackend.ScriptedResponse.json(200, "not json"));
        client = client(null, null);
        assertInternalFailure(client.payments().getQrStatus(GetQrStatusRequest.of("X")));
    }

    @Test
    void getQrStatusUnexpectedObjectFailsClosed() {
        backend.setResponse(TestBackend.ScriptedResponse.json(200, "{\"foo\":1}"));
        client = client(null, null);
        assertInternalFailure(client.payments().getQrStatus(GetQrStatusRequest.of("X")));
    }

    // ---- D. Telefonico Status ----

    @Test
    void getTelefonicoStatusObjectSuccess() {
        backend.setResponse(TestBackend.ScriptedResponse.json(200,
                "{\"detalleResultado\":{\"id\":\"T9\",\"e\":2,\"unknownNest\":{\"y\":9}},\"edoPet\":0}"));
        client = client(null, null);
        Success<BanxicoMessage> s = (Success<BanxicoMessage>) client.payments()
                .getTelefonicoStatus(GetTelefonicoStatusRequest.of("IDC9"));
        assertThat(s.value().transactions()).hasSize(1);
        assertThat(s.value().transactions().get(0).id()).isEqualTo("T9");
        // Unknown nested fields preserved through normalization (B12).
        assertThat(s.value().rawJson()).contains("unknownNest").contains("\"y\":9");
        assertThat(backend.lastRequest().bodyUtf8()).contains("\"idMsjdeCobro\":\"IDC9\"");
    }

    @Test
    void getTelefonicoStatusMissingDetalleFailsClosed() {
        backend.setResponse(TestBackend.ScriptedResponse.json(200, "{\"edoPet\":0}"));
        client = client(null, null);
        assertInternalFailure(client.payments().getTelefonicoStatus(GetTelefonicoStatusRequest.of("X")));
    }

    @Test
    void getTelefonicoStatusMalformedTopLevelFailsClosed() {
        backend.setResponse(TestBackend.ScriptedResponse.json(200, "garbage"));
        client = client(null, null);
        assertInternalFailure(client.payments().getTelefonicoStatus(GetTelefonicoStatusRequest.of("X")));
    }

    // ---- E. Cliente ----

    @Test
    void getClienteCompleteSuccess() {
        backend.setResponse(TestBackend.ScriptedResponse.json(200,
                "{\"id\":7,\"key\":\"K\",\"nombre\":\"N\",\"claveInstitucion\":\"01234\"}"));
        client = client(null, null);
        Success<ClientePerfil> s = (Success<ClientePerfil>) client.clientes().getCliente(GetClienteRequest.of(7L));
        assertThat(s.value().id()).isEqualTo(7L);
        assertThat(s.value().nombre()).isEqualTo("N");
        assertThat(s.value().claveInstitucion()).isEqualTo("01234");
        assertThat(s.value().key()).isEqualTo("K");
        assertThat(backend.lastRequest().path()).isEqualTo("/api/clientes/7");
    }

    @Test
    void getClienteEmptyObjectFailsClosed() {
        backend.setResponse(TestBackend.ScriptedResponse.json(200, "{}"));
        client = client(null, null);
        assertInternalFailure(client.clientes().getCliente(GetClienteRequest.of(1L)));
    }

    @Test
    void getClienteMissingIdFailsClosed() {
        backend.setResponse(TestBackend.ScriptedResponse.json(200,
                "{\"nombre\":\"N\",\"claveInstitucion\":\"01234\",\"key\":\"K\"}"));
        client = client(null, null);
        assertInternalFailure(client.clientes().getCliente(GetClienteRequest.of(1L)));
    }

    @Test
    void getClienteMissingClaveFailsClosed() {
        backend.setResponse(TestBackend.ScriptedResponse.json(200,
                "{\"id\":1,\"nombre\":\"N\",\"key\":\"K\"}"));
        client = client(null, null);
        assertInternalFailure(client.clientes().getCliente(GetClienteRequest.of(1L)));
    }

    // ---- F. Catalogo ----

    @Test
    void getCatalogoValidSuccess() {
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

    @Test
    void getCatalogoEmptyObjectFailsClosed() {
        backend.setResponse(TestBackend.ScriptedResponse.json(200, "{}"));
        client = client(null, null);
        assertInternalFailure(client.catalogos().getCatalogo(GetCatalogoRequest.of("01234")));
    }

    @Test
    void getCatalogoMissingClaveFailsClosed() {
        backend.setResponse(TestBackend.ScriptedResponse.json(200,
                "{\"id\":3,\"nombreCorto\":\"BANCO X\"}"));
        client = client(null, null);
        assertInternalFailure(client.catalogos().getCatalogo(GetCatalogoRequest.of("01234")));
    }

    @Test
    void getCatalogoInvalidClaveRejectedBeforeNetwork() {
        client = client(null, null);
        PaymentOperationResult<CatalogoInstitucion> r =
                client.catalogos().getCatalogo(GetCatalogoRequest.of("a/b"));
        assertThat(r).isInstanceOf(Rejected.class);
        assertThat(((Rejected<?>) r).error()).isInstanceOf(PaymentError.ValidationFailure.class);
        assertThat(backend.requestCount()).isEqualTo(0);
    }

    // ---- H. 202 ----

    @Test
    void createQr202AuthoritativeTypeMapsToPendingReconciliation() {
        backend.setResponse(TestBackend.ScriptedResponse.json(202,
                "{\"type\":\"https://www.jhipster.tech/problem/payment-pending-reconciliation\","
                        + "\"title\":\"pending\",\"detail\":\"reconcile\"}"));
        PaymentOperationResult<BanxicoMessage> r = client(null, null).payments().createQr(qr("k"));
        assertThat(r).isInstanceOf(PendingReconciliation.class);
        assertThat(r.retryAllowed()).isFalse();
        assertThat(((PendingReconciliation<?>) r).code()).isEqualTo(PendingReconciliation.CODE);
    }

    @Test
    void createQr202ArbitraryTypeFailsClosed() {
        backend.setResponse(TestBackend.ScriptedResponse.json(202,
                "{\"type\":\"https://example/other\",\"title\":\"x\"}"));
        PaymentOperationResult<BanxicoMessage> r = client(null, null).payments().createQr(qr("k"));
        assertAmbiguousCreate(r);
    }

    // ---- idempotency ----

    @Test
    void idempotencyKeySentWhenExplicit() {
        backend.setResponse(TestBackend.ScriptedResponse.json(200, CREATE_QR_OBJECT));
        client = client(null, null);
        client.payments().createQr(qr("my-key"));
        assertThat(backend.lastRequest().header("Idempotency-Key")).isEqualTo("my-key");
    }

    @Test
    void idempotencyKeyOmittedForLegacyUnsafe() {
        backend.setResponse(TestBackend.ScriptedResponse.json(200, CREATE_QR_OBJECT));
        client = client(null, null);
        CreateQrRequest legacy = CreateQrRequest.legacyUnsafe(1L, new BigDecimal("1"), "c", 1, "n", "e", 1, 5);
        client.payments().createQr(legacy);
        assertThat(backend.lastRequest().header("Idempotency-Key")).isNull();
    }

    // ---- error mapping (incl. B15 negatives) ----

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
                "{\"type\":\"https://www.jhipster.tech/problem/payment-limit-policy-disabled\",\"detail\":\"d\"}"));
        assertRejectedError(client(null, null).payments().createQr(qr("k")), PaymentError.PaymentLimitPolicyDisabled.class);
    }

    @Test
    void maps400PolicyMissing() {
        backend.setResponse(TestBackend.ScriptedResponse.json(400,
                "{\"type\":\"https://www.jhipster.tech/problem/payment-limit-policy-missing\",\"detail\":\"m\"}"));
        assertRejectedError(client(null, null).payments().createQr(qr("k")), PaymentError.PaymentLimitPolicyMissing.class);
    }

    @Test
    void maps400ConstraintViolation() {
        backend.setResponse(TestBackend.ScriptedResponse.json(400,
                "{\"type\":\"https://www.jhipster.tech/problem/constraint-violation\",\"detail\":\"bad\"}"));
        assertRejectedError(client(null, null).payments().createQr(qr("k")), PaymentError.ValidationFailure.class);
    }

    @Test
    void mapsUnknown409TypeNonRetryable() {
        backend.setResponse(TestBackend.ScriptedResponse.json(409, "{\"detail\":\"weird\"}"));
        PaymentOperationResult<?> r = client(null, null).payments().createQr(qr("k"));
        assertThat(r).isInstanceOf(Rejected.class);
        assertThat(r.retryAllowed()).isFalse();
    }

    @Test
    void mapsUnknown4xxToValidationFailure() {
        backend.setResponse(TestBackend.ScriptedResponse.json(418, "{\"detail\":\"teapot\"}"));
        assertRejectedError(client(null, null).payments().createQr(qr("k")), PaymentError.ValidationFailure.class);
    }

    @Test
    void mapsMalformedRfc7807BodyByStatus() {
        backend.setResponse(TestBackend.ScriptedResponse.json(400, "not json"));
        assertRejectedError(client(null, null).payments().createQr(qr("k")), PaymentError.ValidationFailure.class);
    }

    @Test
    void maps5xxToTransportUnavailable() {
        backend.setResponse(TestBackend.ScriptedResponse.json(503, "{\"detail\":\"down\"}"));
        PaymentOperationResult<?> r = client(null, null).payments().getQrStatus(GetQrStatusRequest.of("X"));
        assertThat(r).isInstanceOf(TransportFailure.class);
        assertThat(((TransportFailure<?>) r).mayHaveBeenSent()).isTrue();
    }

    // ---- I. Security ----

    @Test
    void tokenIsNeverLeakedIntoErrorMessages() {
        backend.setResponse(TestBackend.ScriptedResponse.json(400,
                "{\"type\":\"https://www.jhipster.tech/problem/co-di-amount-limit-exceeded\",\"detail\":\"x\"}"));
        client = client(null, null);
        PaymentOperationResult<BanxicoMessage> r = client.payments().createQr(qr("k"));
        assertThat(((Rejected<?>) r).error().message()).doesNotContain(TOKEN);
        assertThat(backend.lastRequest().header("authorization")).isEqualTo("Bearer " + TOKEN);
        assertThat(backend.lastRequest().bodyUtf8()).doesNotContain(TOKEN);
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

    @Test
    void baseUriMustBeHttpOrHttps() {
        assertThatThrownBy(() -> RestClientConfig.builder()
                .baseUri("file:///etc/passwd").credentials(() -> "x").build())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RestClientConfig.builder()
                .baseUri("https://api.example.com/path?q=1").credentials(() -> "x").build())
                .isInstanceOf(IllegalArgumentException.class);
        // valid https builds fine
        RestClientConfig ok = RestClientConfig.builder()
                .baseUri("https://api.example.com").credentials(() -> "x").build();
        assertThat(ok.baseUri().getScheme()).isEqualTo("https");
    }

    // ---- helpers ----

    private static void assertInternalFailure(PaymentOperationResult<?> r) {
        assertThat(r).isInstanceOf(Rejected.class);
        assertThat(((Rejected<?>) r).error()).isInstanceOf(PaymentError.InternalFailure.class);
        assertThat(r.retryAllowed()).isFalse();
    }

    private static void assertRejectedError(PaymentOperationResult<?> r, Class<? extends PaymentError> errorType) {
        assertThat(r).isInstanceOf(Rejected.class);
        assertThat(((Rejected<?>) r).error()).isInstanceOf(errorType);
        assertThat(r.retryAllowed()).isFalse();
    }
}
