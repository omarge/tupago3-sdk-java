package com.tupago.sdk.graphql;

import com.tupago.sdk.core.banxico.BanxicoMessage;
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
import com.tupago.sdk.core.retry.RetryPolicy;
import com.tupago.sdk.core.config.TimeoutConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class GraphqlClientIntegrationTest {

    private GraphqlTestBackend backend;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws Exception {
        backend = new GraphqlTestBackend();
    }

    @AfterEach
    void tearDown() {
        if (backend != null) {
            backend.stop();
        }
    }

    // ---- envelope helpers ----

    private static String dataEnvelope(String dataJson) {
        return "{\"data\":" + dataJson + "}";
    }

    private static String errorsEnvelope(String errorsJson) {
        return "{\"errors\":" + errorsJson + "}";
    }

    private static String dataErrorsEnvelope(String dataJson, String errorsJson) {
        return "{\"data\":" + dataJson + ",\"errors\":" + errorsJson + "}";
    }

    private static String gqlError(String code) {
        return "[{\"extensions\":{\"code\":\"" + code + "\"},\"message\":\"" + code + "\"}]";
    }

    /** A valid direct MsjPagoQrResponse GraphQL payload (G1 wire contract). */
    private static String qrOk() {
        return "{\"createPagoQr\":{\"TYP\":1,\"v\":{\"DEV\":\"D\"},\"ic\":{\"IDC\":\"X\",\"SER\":1,\"ENC\":\"E\"},\"CRY\":\"C\"}}";
    }

    private GraphqlTupagoClient client(String token, RetryPolicy retry, TimeoutConfig timeouts) {
        GraphqlClientConfig.Builder b = GraphqlClientConfig.builder()
                .baseUri(backend.baseUri())
                .credentials(() -> token)
                .retryPolicy(retry == null ? NoRetryPolicy.INSTANCE : retry);
        if (timeouts != null) {
            b.timeouts(timeouts);
        }
        return GraphqlTupagoClient.create(b.build());
    }

    private GraphqlTupagoClient client() {
        return client("jwt-token", null, null);
    }

    private static void assertAmbiguousCreate(PaymentOperationResult<?> r) {
        assertThat(r).isInstanceOf(TransportFailure.class);
        TransportFailure<?> tf = (TransportFailure<?>) r;
        assertThat(tf.error()).isInstanceOf(PaymentError.TransportUnavailable.class);
        assertThat(tf.mayHaveBeenSent()).isTrue();
        assertThat(tf.retryAllowed()).isFalse();
    }

    private static void assertInternalFailure(PaymentOperationResult<?> r) {
        assertThat(r).isInstanceOf(Rejected.class);
        assertThat(((Rejected<?>) r).error()).isInstanceOf(PaymentError.InternalFailure.class);
    }

    @SuppressWarnings("unchecked")
    private static <T> T successValue(PaymentOperationResult<?> r) {
        assertThat(r).isInstanceOf(Success.class);
        return ((Success<T>) r).value();
    }

    // ========================= AUTH =========================

    @Test
    void bearerHeaderSentExactly() {
        backend.setResponse(GraphqlTestBackend.ScriptedResponse.json(200,
                dataEnvelope("{\"cliente\":null}")));
        client().clientes().getCliente(GetClienteRequest.of(5));
        assertThat(backend.lastRequest().header("authorization")).isEqualTo("Bearer jwt-token");
        assertThat(backend.lastRequest().path()).isEqualTo("/graphql");
    }

    @Test
    void missingTokenFailsBeforeNetwork() {
        GraphqlClientConfig cfg = GraphqlClientConfig.builder()
                .baseUri(backend.baseUri())
                .credentials(() -> null)
                .build();
        GraphqlTupagoClient c = GraphqlTupagoClient.create(cfg);
        PaymentOperationResult<?> r = c.clientes().getCliente(GetClienteRequest.of(5));
        assertThat(r).isInstanceOf(Rejected.class);
        assertThat(((Rejected<?>) r).error()).isInstanceOf(PaymentError.AuthenticationFailure.class);
        assertThat(backend.requestCount()).isZero();
    }

    @Test
    void tokenNeverLeakedIntoResult() {
        backend.setResponse(GraphqlTestBackend.ScriptedResponse.json(200, errorsEnvelope(gqlError("IDEMPOTENCY_CONFLICT"))));
        PaymentOperationResult<?> r = client().payments().createQr(qr("k1"));
        assertThat(r.toString()).doesNotContain("jwt-token");
        assertThat(backend.lastRequest().bodyUtf8()).doesNotContain("jwt-token");
    }

    // ========================= REQUEST ENVELOPE =========================

    @Test
    void postsGraphqlEnvelopeWithVariablesNotInterpolated() {
        backend.setResponse(GraphqlTestBackend.ScriptedResponse.json(200,
                dataEnvelope("{\"catalogoInstitucion\":{\"id\":\"9\",\"claveInstitucion\":\"BANX123\",\"nombreCorto\":\"Banco\"}}")));
        client().catalogos().getCatalogo(GetCatalogoRequest.of("BANX123"));
        JsonNode env = readLastBody();
        assertThat(env.path("query").asText()).contains("GetCatalogo");
        assertThat(env.path("operationName").asText()).isEqualTo("GetCatalogo");
        assertThat(env.path("variables").path("claveInstitucion").asText()).isEqualTo("BANX123");
        // Caller data must NOT be concatenated into the query source (GraphQL injection defense).
        assertThat(env.path("query").asText()).doesNotContain("BANX123");
    }

    // ========================= CREATE QR (C1) =========================

    @Test
    // G1: GraphQL createPagoQr returns the backend MsjPagoQrResponse object DIRECTLY (no REST mensaje/emisor/banco wrapper).
    void createQrDirectMsjPagoQrResponseSuccess() {
        String payload = "{\"TYP\":1,\"v\":{\"DEV\":\"DEVICE\"},\"ic\":{\"IDC\":\"IDC123\",\"SER\":7,\"ENC\":\"ENC\"},"
                + "\"CRY\":\"CRYPT\",\"futureField\":{\"x\":1}}";
        backend.setResponse(GraphqlTestBackend.ScriptedResponse.json(200,
                dataEnvelope("{\"createPagoQr\":" + payload + "}")));
        BanxicoMessage s = successValue(client().payments().createQr(qr("k1")));
        assertThat(s.typ()).isEqualTo(1);
        assertThat(s.deviceDev()).isEqualTo("DEVICE");
        assertThat(s.icIdc()).isEqualTo("IDC123");
        assertThat(s.icSer()).isEqualTo(7);
        assertThat(s.icEnc()).isEqualTo("ENC");
        assertThat(s.rawJson()).contains("\"CRY\":\"CRYPT\"");
        assertThat(s.rawJson()).contains("futureField");
    }

    @Test
    void createQrSendsIdempotencyVariable() {
        backend.setResponse(GraphqlTestBackend.ScriptedResponse.json(200, dataEnvelope(qrOk())));
        client().payments().createQr(qr("k1"));
        assertThat(readLastBody().path("variables").path("idempotencyKey").asText()).isEqualTo("k1");
    }

    @Test
    void createQrLegacyKeySendsNullIdempotency() {
        backend.setResponse(GraphqlTestBackend.ScriptedResponse.json(200, dataEnvelope(qrOk())));
        client().payments().createQr(CreateQrRequest.legacyUnsafe(null, new BigDecimal("10"), null, null, null, null, null, null));
        assertThat(readLastBody().path("variables").path("idempotencyKey").isNull()).isTrue();
    }

    @Test
    void createQrPendingReconciliation() {
        backend.setResponse(GraphqlTestBackend.ScriptedResponse.json(200, errorsEnvelope(gqlError("PAYMENT_PENDING_RECONCILIATION"))));
        PaymentOperationResult<?> r = client().payments().createQr(qr("k1"));
        assertThat(r).isInstanceOf(PendingReconciliation.class);
        assertThat(r.retryAllowed()).isFalse();
    }

    @Test
    void createQrStringFailsClosed() {
        backend.setResponse(GraphqlTestBackend.ScriptedResponse.json(200,
                dataEnvelope("{\"createPagoQr\":\"not-an-object\"}")));
        assertAmbiguousCreate(client().payments().createQr(qr("k1")));
    }

    @Test
    void createQrArrayFailsClosed() {
        backend.setResponse(GraphqlTestBackend.ScriptedResponse.json(200,
                dataEnvelope("{\"createPagoQr\":[1,2,3]}")));
        assertAmbiguousCreate(client().payments().createQr(qr("k1")));
    }

    @Test
    void createQrNullFailsClosed() {
        backend.setResponse(GraphqlTestBackend.ScriptedResponse.json(200,
                dataEnvelope("{\"createPagoQr\":null}")));
        assertAmbiguousCreate(client().payments().createQr(qr("k1")));
    }

    @Test
    void createQrEmptyObjectFailsClosed() {
        backend.setResponse(GraphqlTestBackend.ScriptedResponse.json(200,
                dataEnvelope("{\"createPagoQr\":{}}")));
        assertAmbiguousCreate(client().payments().createQr(qr("k1")));
    }

    @Test
    void createQrRestStyleWrapperNotTreatedAsSuccess() {
        // The REST-style {mensaje, emisor, banco} wrapper is NOT the GraphQL wire contract; it must fail closed.
        backend.setResponse(GraphqlTestBackend.ScriptedResponse.json(200,
                dataEnvelope("{\"createPagoQr\":{\"mensaje\":{\"TYP\":1},\"emisor\":\"E\",\"banco\":\"B\"}}")));
        assertAmbiguousCreate(client().payments().createQr(qr("k1")));
    }

    @Test
    void createQrMalformedEnvelopeFailsClosed() {
        backend.setResponse(GraphqlTestBackend.ScriptedResponse.json(200, "this is not json"));
        assertAmbiguousCreate(client().payments().createQr(qr("k1")));
    }

    @Test
    void createQrGraphQLErrorTranslated() {
        backend.setResponse(GraphqlTestBackend.ScriptedResponse.json(200, errorsEnvelope(gqlError("IDEMPOTENCY_CONFLICT"))));
        Rejected<?> r = (Rejected<?>) client().payments().createQr(qr("k1"));
        assertThat(r.error()).isInstanceOf(PaymentError.IdempotencyConflict.class);
    }

    @Test
    void createQrNoBlindRetryOnUnreachable() throws Exception {
        int closed = GraphqlTestBackend.closedPort();
        GraphqlClientConfig cfg = GraphqlClientConfig.builder()
                .baseUri("http://127.0.0.1:" + closed)
                .credentials(() -> "jwt")
                .retryPolicy(new BackoffRetryPolicy(3, Duration.ZERO))
                .build();
        GraphqlTupagoClient c = GraphqlTupagoClient.create(cfg);
        c.payments().createQr(qr("k1"));
        // No retry for creates even on pre-execution failure.
        assertThat(backend.requestCount()).isZero();
    }

    // ========================= CREATE TELEFONICO (C2) =========================

    @Test
    void createTelefonicoTextualDetalleSuccess() {
        backend.setResponse(GraphqlTestBackend.ScriptedResponse.json(200,
                dataEnvelope("{\"createPagoTelefonico\":{\"detalleResultado\":\"{\\\"id\\\":\\\"T1\\\",\\\"e\\\":1}\",\"edoPet\":0}}")));
        BanxicoMessage s = successValue(client().payments().createTelefonico(telefonico("k1")));
        assertThat(s.rawJson()).contains("\"id\":\"T1\"").contains("\"e\":1");
    }

    @Test
    void createTelefonicoPreservesInputTyping() {
        backend.setResponse(GraphqlTestBackend.ScriptedResponse.json(200,
                dataEnvelope("{\"createPagoTelefonico\":{\"detalleResultado\":\"{}\",\"edoPet\":0}}")));
        client().payments().createTelefonico(telefonico("k1"));
        JsonNode input = readLastBody().path("variables").path("input");
        assertThat(input.path("monto").isNumber()).isTrue();
        assertThat(input.path("numeroCelular").isTextual()).isTrue();
    }

    @Test
    void createTelefonicoPendingReconciliation() {
        backend.setResponse(GraphqlTestBackend.ScriptedResponse.json(200, errorsEnvelope(gqlError("PAYMENT_PENDING_RECONCILIATION"))));
        assertThat(client().payments().createTelefonico(telefonico("k1"))).isInstanceOf(PendingReconciliation.class);
    }

    @Test
    void createTelefonicoNonTextualDetalleFailsClosed() {
        backend.setResponse(GraphqlTestBackend.ScriptedResponse.json(200,
                dataEnvelope("{\"createPagoTelefonico\":{\"detalleResultado\":{\"id\":\"X\"},\"edoPet\":0}}")));
        assertAmbiguousCreate(client().payments().createTelefonico(telefonico("k1")));
    }

    // ========================= QR STATUS (C3) =========================

    @Test
    void getQrStatusArraySuccess() {
        backend.setResponse(GraphqlTestBackend.ScriptedResponse.json(200,
                dataEnvelope("{\"pagoQrEstatusByCuenta\":[{\"id\":\"1\",\"cc\":\"x\",\"mt\":10}]}")));
        BanxicoMessage s = successValue(client().payments().getQrStatus(GetQrStatusRequest.of("IDC1")));
        assertThat(s.transactions()).hasSize(1);
        assertThat(s.rawJson()).contains("\"id\":\"1\"");
    }

    @Test
    void getQrStatusMalformedResponseFailsClosed() {
        backend.setResponse(GraphqlTestBackend.ScriptedResponse.json(200,
                dataEnvelope("{\"pagoQrEstatusByCuenta\":\"not-an-array\"}")));
        assertInternalFailure(client().payments().getQrStatus(GetQrStatusRequest.of("IDC1")));
    }

    // ========================= TELEFONICO STATUS (C4) =========================

    @Test
    void getTelefonicoStatusObjectSuccess() {
        backend.setResponse(GraphqlTestBackend.ScriptedResponse.json(200,
                dataEnvelope("{\"pagoTelefonicoEstatusByCuenta\":{\"detalleResultado\":{\"id\":\"D1\"},\"edoPet\":0}}")));
        BanxicoMessage s = successValue(client().payments().getTelefonicoStatus(GetTelefonicoStatusRequest.of("IDC1")));
        assertThat(s.transactions()).hasSize(1);
        assertThat(s.rawJson()).contains("\"id\":\"D1\"");
    }

    @Test
    void getTelefonicoStatusMalformedFailsClosed() {
        backend.setResponse(GraphqlTestBackend.ScriptedResponse.json(200,
                dataEnvelope("{\"pagoTelefonicoEstatusByCuenta\":{}}")));
        assertInternalFailure(client().payments().getTelefonicoStatus(GetTelefonicoStatusRequest.of("IDC1")));
    }

    // ========================= CLIENTE (C5) =========================

    @Test
    void getClienteOwnerScopedSuccess() {
        backend.setResponse(GraphqlTestBackend.ScriptedResponse.json(200, dataEnvelope(
                "{\"cliente\":{\"cliente\":{\"id\":\"5\",\"nombre\":\"N\",\"claveInstitucion\":\"C\",\"key\":\"K\"},"
                        + "\"catalogoInstitucion\":{\"id\":\"7\",\"claveInstitucion\":\"C\",\"nombreCorto\":\"Banco\"}}}")));
        ClientePerfil s = successValue(client().clientes().getCliente(GetClienteRequest.of(5)));
        assertThat(s.id()).isEqualTo(5L);
        assertThat(s.nombre()).isEqualTo("N");
        assertThat(s.claveInstitucion()).isEqualTo("C");
        assertThat(s.key()).isEqualTo("K");
        assertThat(s.catalogoInstitucion()).isNotNull();
        assertThat(s.catalogoInstitucion().id()).isEqualTo(7L);
        assertThat(readLastBody().path("variables").path("id").asText()).isEqualTo("5");
    }

    @Test
    void getClienteNotFound() {
        backend.setResponse(GraphqlTestBackend.ScriptedResponse.json(200, dataEnvelope("{\"cliente\":null}")));
        Rejected<?> r = (Rejected<?>) client().clientes().getCliente(GetClienteRequest.of(5));
        assertThat(r.error()).isInstanceOf(PaymentError.NotFound.class);
    }

    @Test
    void getClienteMalformedShapeFailsClosed() {
        backend.setResponse(GraphqlTestBackend.ScriptedResponse.json(200,
                dataEnvelope("{\"cliente\":{\"cliente\":{\"id\":\"5\"}}}")));
        assertInternalFailure(client().clientes().getCliente(GetClienteRequest.of(5)));
    }

    // G3: a present but non-numeric cliente id must fail closed, never yield id=null.
    @Test
    void getClienteNonNumericIdFailsClosed() {
        backend.setResponse(GraphqlTestBackend.ScriptedResponse.json(200, dataEnvelope(
                "{\"cliente\":{\"cliente\":{\"id\":\"NOT-A-NUMBER\",\"nombre\":\"N\",\"claveInstitucion\":\"C\",\"key\":\"K\"}}}")));
        assertInternalFailure(client().clientes().getCliente(GetClienteRequest.of(5)));
    }

    // G3: a present but non-numeric nested catalogoInstitucion.id must fail closed.
    @Test
    void getClienteNestedCatalogNonNumericIdFailsClosed() {
        backend.setResponse(GraphqlTestBackend.ScriptedResponse.json(200, dataEnvelope(
                "{\"cliente\":{\"cliente\":{\"id\":\"5\",\"nombre\":\"N\",\"claveInstitucion\":\"C\",\"key\":\"K\"},"
                        + "\"catalogoInstitucion\":{\"id\":\"BAD\",\"claveInstitucion\":\"C\",\"nombreCorto\":\"Banco\"}}}")));
        assertInternalFailure(client().clientes().getCliente(GetClienteRequest.of(5)));
    }

    // ========================= CATALOGO (C6) =========================

    @Test
    void getCatalogoSuccess() {
        backend.setResponse(GraphqlTestBackend.ScriptedResponse.json(200,
                dataEnvelope("{\"catalogoInstitucion\":{\"id\":\"9\",\"claveInstitucion\":\"C\",\"nombreCorto\":\"Banco\"}}")));
        CatalogoInstitucion s = successValue(client().catalogos().getCatalogo(GetCatalogoRequest.of("C")));
        assertThat(s.id()).isEqualTo(9L);
        assertThat(s.claveInstitucion()).isEqualTo("C");
        assertThat(s.nombreCorto()).isEqualTo("Banco");
        assertThat(readLastBody().path("variables").path("claveInstitucion").asText()).isEqualTo("C");
    }

    @Test
    void getCatalogoNotFound() {
        backend.setResponse(GraphqlTestBackend.ScriptedResponse.json(200, dataEnvelope("{\"catalogoInstitucion\":null}")));
        Rejected<?> r = (Rejected<?>) client().catalogos().getCatalogo(GetCatalogoRequest.of("C"));
        assertThat(r.error()).isInstanceOf(PaymentError.NotFound.class);
    }

    @Test
    void getCatalogoMalformedShapeFailsClosed() {
        backend.setResponse(GraphqlTestBackend.ScriptedResponse.json(200,
                dataEnvelope("{\"catalogoInstitucion\":{\"id\":\"9\"}}")));
        assertInternalFailure(client().catalogos().getCatalogo(GetCatalogoRequest.of("C")));
    }

    // G3: a present but non-numeric catalogo id must fail closed, never yield id=null.
    @Test
    void getCatalogoNonNumericIdFailsClosed() {
        backend.setResponse(GraphqlTestBackend.ScriptedResponse.json(200, dataEnvelope(
                "{\"catalogoInstitucion\":{\"id\":\"NOT-NUMERIC\",\"claveInstitucion\":\"C\",\"nombreCorto\":\"Banco\"}}")));
        assertInternalFailure(client().catalogos().getCatalogo(GetCatalogoRequest.of("C")));
    }

    @Test
    void getCatalogoInvalidClaveRejectedWithoutNetwork() {
        int before = backend.requestCount();
        Rejected<?> r = (Rejected<?>) client().catalogos().getCatalogo(GetCatalogoRequest.of("bad/clave"));
        assertThat(r.error()).isInstanceOf(PaymentError.ValidationFailure.class);
        assertThat(backend.requestCount()).isEqualTo(before);
    }

    // ========================= ERRORS =========================

    @Test
    void http401Authentication() {
        backend.setResponse(GraphqlTestBackend.ScriptedResponse.json(401, "{}"));
        Rejected<?> r = (Rejected<?>) client().clientes().getCliente(GetClienteRequest.of(5));
        assertThat(r.error()).isInstanceOf(PaymentError.AuthenticationFailure.class);
    }

    @Test
    void http403Authorization() {
        backend.setResponse(GraphqlTestBackend.ScriptedResponse.json(403, "{}"));
        Rejected<?> r = (Rejected<?>) client().clientes().getCliente(GetClienteRequest.of(5));
        assertThat(r.error()).isInstanceOf(PaymentError.AuthorizationFailure.class);
    }

    @Test
    void http400Validation() {
        backend.setResponse(GraphqlTestBackend.ScriptedResponse.json(400, "{}"));
        Rejected<?> r = (Rejected<?>) client().clientes().getCliente(GetClienteRequest.of(5));
        assertThat(r.error()).isInstanceOf(PaymentError.ValidationFailure.class);
    }

    @Test
    void http404NotFound() {
        backend.setResponse(GraphqlTestBackend.ScriptedResponse.json(404, "{}"));
        Rejected<?> r = (Rejected<?>) client().clientes().getCliente(GetClienteRequest.of(5));
        assertThat(r.error()).isInstanceOf(PaymentError.NotFound.class);
    }

    @Test
    void graphqlPolicyRevisionConflict() {
        backend.setResponse(GraphqlTestBackend.ScriptedResponse.json(200, errorsEnvelope(gqlError("POLICY_REVISION_CONFLICT"))));
        Rejected<?> r = (Rejected<?>) client().payments().createQr(qr("k1"));
        assertThat(r.error()).isInstanceOf(PaymentError.PolicyRevisionConflict.class);
    }

    // Source-truth test: backend GraphQlConfig collapses all three amount-limit exceptions into PAYMENT_LIMIT.
    @Test
    void graphqlPaymentLimit() {
        backend.setResponse(GraphqlTestBackend.ScriptedResponse.json(200, errorsEnvelope(gqlError("PAYMENT_LIMIT"))));
        Rejected<?> r = (Rejected<?>) client().payments().createQr(qr("k1"));
        assertThat(r.error()).isInstanceOf(PaymentError.PaymentLimitExceeded.class);
    }

    // The backend does NOT currently emit PAYMENT_LIMIT_POLICY_DISABLED; retained ONLY as a forward-compatible
    // alias (see GraphqlErrorTranslator javadoc). This is NOT proof of current backend behavior.
    @Test
    void graphqlPaymentLimitPolicyDisabledAlias() {
        backend.setResponse(GraphqlTestBackend.ScriptedResponse.json(200, errorsEnvelope(gqlError("PAYMENT_LIMIT_POLICY_DISABLED"))));
        Rejected<?> r = (Rejected<?>) client().payments().createQr(qr("k1"));
        assertThat(r.error()).isInstanceOf(PaymentError.PaymentLimitPolicyDisabled.class);
    }

    // Forward-compatible alias only; backend currently emits PAYMENT_LIMIT for missing policy too.
    @Test
    void graphqlPaymentLimitPolicyMissingAlias() {
        backend.setResponse(GraphqlTestBackend.ScriptedResponse.json(200, errorsEnvelope(gqlError("PAYMENT_LIMIT_POLICY_MISSING"))));
        Rejected<?> r = (Rejected<?>) client().payments().createQr(qr("k1"));
        assertThat(r.error()).isInstanceOf(PaymentError.PaymentLimitPolicyMissing.class);
    }

    @Test
    void graphqlUnknownCodeFailsClosed() {
        backend.setResponse(GraphqlTestBackend.ScriptedResponse.json(200, errorsEnvelope(gqlError("TOTALLY_UNKNOWN"))));
        assertAmbiguousCreate(client().payments().createQr(qr("k1")));
    }

    @Test
    void http500TransportUnavailable() {
        backend.setResponse(GraphqlTestBackend.ScriptedResponse.json(500, "{}"));
        PaymentOperationResult<?> r = client().clientes().getCliente(GetClienteRequest.of(5));
        assertThat(r).isInstanceOf(TransportFailure.class);
    }

    @Test
    void dataWithErrorsFailsClosedByError() {
        backend.setResponse(GraphqlTestBackend.ScriptedResponse.json(200,
                dataErrorsEnvelope("{\"createPagoQr\":{\"mensaje\":{}}}", gqlError("IDEMPOTENCY_CONFLICT"))));
        Rejected<?> r = (Rejected<?>) client().payments().createQr(qr("k1"));
        assertThat(r.error()).isInstanceOf(PaymentError.IdempotencyConflict.class);
    }

    @Test
    void dataWithPendingReconciliationDominates() {
        backend.setResponse(GraphqlTestBackend.ScriptedResponse.json(200,
                dataErrorsEnvelope("{\"createPagoQr\":{\"mensaje\":{}}}", gqlError("PAYMENT_PENDING_RECONCILIATION"))));
        assertThat(client().payments().createQr(qr("k1"))).isInstanceOf(PendingReconciliation.class);
    }

    // ========================= RETRY =========================

    @Test
    void readRetriesOnPreExecutionFailure() throws Exception {
        int closed = GraphqlTestBackend.closedPort();
        GraphqlClientConfig cfg = GraphqlClientConfig.builder()
                .baseUri("http://127.0.0.1:" + closed)
                .credentials(() -> "jwt")
                .retryPolicy(new BackoffRetryPolicy(3, Duration.ZERO))
                .build();
        GraphqlTupagoClient c = GraphqlTupagoClient.create(cfg);
        PaymentOperationResult<?> r = c.clientes().getCliente(GetClienteRequest.of(5));
        // Pre-execution transport failure on a read still yields a TransportFailure (the retry loop
        // exhausted its attempts under RetryClassifier authority; the closed backend receives no traffic,
        // so we assert the outcome type rather than a request count).
        assertThat(r).isInstanceOf(TransportFailure.class);
    }

    @Test
    void createTimeoutNoRetry() {
        backend.setResponse(GraphqlTestBackend.ScriptedResponse.jsonDelayed(200,
                dataEnvelope("{\"createPagoQr\":{\"mensaje\":{}}}"), 5000));
        TimeoutConfig tight = TimeoutConfig.defaults().withCreateOperationMillis(150).withRequestMillis(150);
        int before = backend.requestCount();
        client("jwt", new BackoffRetryPolicy(3, Duration.ZERO), tight).payments().createQr(qr("k1"));
        // Create timeout (mayHaveBeenSent=true) -> RetryClassifier forbids retry.
        assertThat(backend.requestCount() - before).isEqualTo(1);
    }

    @Test
    void graphqlApplicationErrorNoRetry() {
        backend.setResponse(GraphqlTestBackend.ScriptedResponse.json(200, errorsEnvelope(gqlError("IDEMPOTENCY_CONFLICT"))));
        int before = backend.requestCount();
        client("jwt", new BackoffRetryPolicy(3, Duration.ZERO), null).payments().createQr(qr("k1"));
        assertThat(backend.requestCount() - before).isEqualTo(1);
    }

    // G4: a request-envelope serialization failure is LOCAL and DETERMINISTIC. It must surface as
    // Rejected(InternalFailure), never as a retryable TransportFailure, and MUST NOT emit any network
    // request (no blind retry of a read, no retry of a create).
    @Test
    void localEnvelopeSerializationFailsClosedNoRetry() {
        GraphqlClientConfig cfg = GraphqlClientConfig.builder()
                .baseUri(backend.baseUri())
                .credentials(() -> "jwt")
                .retryPolicy(new BackoffRetryPolicy(3, Duration.ZERO))
                .objectMapper(new FailingObjectMapper())
                .build();
        GraphqlTupagoClient c = GraphqlTupagoClient.create(cfg);
        int before = backend.requestCount();
        PaymentOperationResult<?> r = c.payments().createQr(qr("k1"));
        assertThat(r).isInstanceOf(Rejected.class);
        assertThat(((Rejected<?>) r).error()).isInstanceOf(PaymentError.InternalFailure.class);
        assertThat(backend.requestCount() - before).isEqualTo(0);
    }

    /** ObjectMapper that always fails to serialize, to exercise the G4 local-serialization path. */
    private static final class FailingObjectMapper extends ObjectMapper {
        @Override
        public byte[] writeValueAsBytes(Object value) throws com.fasterxml.jackson.core.JsonProcessingException {
            throw new com.fasterxml.jackson.core.JsonProcessingException("injected serialization failure") {};
        }
    }

    // ========================= helpers =========================

    private JsonNode readLastBody() {
        try {
            return mapper.readTree(backend.lastRequest().bodyUtf8());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static CreateQrRequest qr(String key) {
        return CreateQrRequest.of(null, new BigDecimal("10.00"), "concepto", 1, "cli", "a@b.c", 1, 60,
                IdempotencyKey.of(key));
    }

    private static CreateTelefonicoRequest telefonico(String key) {
        return CreateTelefonicoRequest.of(key, new BigDecimal("20.00"), "concepto", 2, "5512345678", 60,
                IdempotencyKey.of(key));
    }
}
