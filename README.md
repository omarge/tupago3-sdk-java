# TUPAGO3 Java SDK

Transport-neutral Java client SDK for the TUPAGO3 payments platform, covering the six canonical
capabilities (`CreateQr`, `CreateTelefonico`, `GetQrStatus`, `GetTelefonicoStatus`, `GetCliente`,
`GetCatalogo`). The SDK is certified on **Java 21 and Java 25** runtimes with **Java 21 bytecode**
(classfile major version 65). It is intended for **controlled internal distribution** under a
**Proprietary** license (see `LICENSE`).

The wire contract is frozen against backend `omarge/tupagoback3` master
`bbf06c02bda06221b2f214075a4bf2bdf3345eb0` (gRPC `payment.proto`, package `tupago.api.v1`).

---

## 1. Module topology

| Module / artifact | Role | Transport |
|-------------------|------|-----------|
| `tupago-sdk-core` (`com.tupago:tupago-sdk-core`) | Transport-neutral contracts: `TupagoClient`, `PaymentOperationResult`, `PaymentError`, `RetryClassifier`, `IdempotencyKey`, `CredentialProvider`, Banxico model. | none |
| `tupago-sdk-rest` (`com.tupago:tupago-sdk-rest`) | REST adapter (`RestTupagoClient`) over JDK `HttpClient`. | REST |
| `tupago-sdk-graphql` (`com.tupago:tupago-sdk-graphql`) | GraphQL-over-HTTP adapter (`GraphqlTupagoClient`). | GraphQL |
| root `tupago3-sdk-java` (`com.tupago:tupago3-sdk-java`) | Modern gRPC facade (`GrpcTupagoClient`) **and** legacy gRPC compatibility client (`TupagoPaymentClient`). | gRPC |

Dependency direction (enforced by architecture tests):

```
core  ->  (no transport: no REST/GraphQL/gRPC/Spring/Netty/protobuf/grpc-java)

REST     -> core only
GraphQL  -> core only
gRPC(root) -> core only

REST must NOT depend on GraphQL/gRPC.
GraphQL must NOT depend on REST/gRPC.
```

---

## 2. Java requirements

- **Minimum runtime / bytecode: Java 21** (`options.release = 21`).
- Certified build/test runtimes: **Java 21** and **Java 25**.
- Build tool: Gradle 9.x. Runtime/toolchain is selectable:

```bash
./gradlew clean build -PjavaVersion=21 --no-daemon   # JDK 21
./gradlew clean build -PjavaVersion=25 --no-daemon   # JDK 25 (still emits Java 21 bytecode)
```

All four projects compile with `options.release = 21`. The CI matrix runs both Java 21 and
Java 25 with `fail-fast: false`, so both legs are always reported.

---

## 3. Install — Gradle

```gradle
repositories {
    mavenLocal()      // internal distribution
    mavenCentral()    // transitive dependencies
}

dependencies {
    // pick the transport(s) you need; core is pulled transitively
    implementation 'com.tupago:tupago-sdk-core:0.1.0'      // contracts only
    implementation 'com.tupago:tupago-sdk-rest:0.1.0'
    implementation 'com.tupago:tupago-sdk-graphql:0.1.0'
    implementation 'com.tupago:tupago3-sdk-java:0.1.0'     // gRPC facade + legacy client
}
```

## 4. Install — Maven

```xml
<repositories>
  <repository>
    <id>local</id>
    <url>file://${user.home}/.m2/repository</url>
  </repository>
</repositories>

<dependency>
  <groupId>com.tupago</groupId>
  <artifactId>tupago-sdk-rest</artifactId>
  <version>0.1.0</version>
</dependency>
```

---

## 5. Six canonical capabilities (cross-transport)

| Capability | REST | GraphQL | gRPC |
|------------|------|---------|------|
| `C1 CreateQr` | POST `/api/pago/qr` | `createQr` mutation | `CreateQr` RPC |
| `C2 CreateTelefonico` | POST `/api/pago/telefonico` | `createTelefonico` mutation | `CreateTelefonico` RPC |
| `C3 GetQrStatus` | GET status (Banxico JSON) | `getQrStatus` query | `GetQrStatus` RPC (Banxico JSON) |
| `C4 GetTelefonicoStatus` | GET status (Banxico JSON) | `getTelefonicoStatus` query | `GetTelefonicoStatus` RPC (Banxico JSON) |
| `C5 GetCliente` | GET cliente profile | `getCliente` query | `GetCliente` RPC |
| `C6 GetCatalogo` | GET institution catalog | `getCatalogo` query | `GetCatalogo` RPC |

**Owner/account scope:** all operations are owner-scoped via the caller's JWT. gRPC status reads
(`GetQrStatus`/`GetTelefonicoStatus`) additionally require a non-null `idCuenta`; the gRPC adapter
rejects `idCuenta == null` locally as a `ValidationFailure` **before** any network call. REST/GraphQL
status reads follow their transport contract for optional `idCuenta`.

**Idempotency:** `CreateQr`/`CreateTelefonico` take a caller-provided `IdempotencyKey`. The caller's
key is authoritative; the SDK never generates or retries it.

**PendingReconciliation:** surfaced as `PendingReconciliation` when the backend returns `ABORTED`
with the stable `TUPAGO_PENDING_RECONCILIATION` token (gRPC) or the equivalent non-transient state
on REST/GraphQL. It is **never** retried.

**Timeout:** every transport honors a per-request deadline (`RestClientConfig`/`GraphqlClientConfig`/
`GrpcClientConfig` deadline settings).

**Retry law (`RetryClassifier` is the sole semantic authority):** creates are **never** blind
auto-retried. A create that fails with an unknown outcome is surfaced as a failure the caller must
reconcile (reuse the same `IdempotencyKey` or query status) — never blind resubmit. For reads, a
transport failure (e.g. gRPC `UNAVAILABLE`/`DEADLINE_EXCEEDED`, mapped to `TransportUnavailable`) is
retried **only** when `RetryClassifier` returns `RETRY`, which happens **only** when the transport
proves the request was **not** sent (`mayHaveBeenSent == false`). Because the gRPC adapter maps those
failures with `mayHaveBeenSent = true`, it effectively does **not** speculative-retry reads; reconcile
by status query is the safe path. Reads are therefore NOT blanket-retried on every transient error.

**Known semantic collapses (backend-specific, not invented):**
- GraphQL `PAYMENT_LIMIT` collapses: amount-exceeded / policy-disabled / policy-missing. The SDK
  cannot distinguish these through a stable GraphQL code.
- gRPC `ALREADY_EXISTS` collapses: idempotency-conflict / policy-revision-conflict → `InternalFailure`.
- gRPC `FAILED_PRECONDITION` collapses: amount-exceeded / policy-disabled / policy-missing →
  `PaymentLimitExceeded`.
- gRPC `ABORTED` without the pending token → `Rejected(InternalFailure)` (fail closed).

---

## 6. Modern API vs legacy gRPC API

**Modern (recommended for new applications):** the transport-neutral core model
(`PaymentOperationResult`, `Success`/`Rejected`/`TransportFailure`/`PendingReconciliation`,
`PaymentError`), the unified `TupagoClient` interface, and the transport adapters
`RestTupagoClient`, `GraphqlTupagoClient`, `GrpcTupagoClient` with their `*ClientConfig` builders.
Retry semantics are defined by `RetryClassifier`.

**Legacy (compatibility only — do NOT adopt for new apps):** `com.tupago.sdk.client.TupagoPaymentClient`
plus generated protobuf types (`com.tupago.sdk.grpc.v1.*`) and the `TupagoException` hierarchy. It is
retained for backward compatibility with prior integrations and is marked deprecated where applicable.
Its retry/exception behavior is **not** the canonical financial policy for new applications — use the
core-facing API instead.

---

## 7. Minimal modern usage (gRPC facade)

The modern gRPC facade returns **only** transport-neutral core types. There is no protobuf/gRPC type
leak: `GrpcTupagoClient` implements `TupagoClient`, and `payments()`/`clientes()`/`catalogos()` return
core operation interfaces. Configure via `GrpcClientConfig` and build via `GrpcTupagoClient.create(...)`.
Deadlines are set through `TimeoutConfig` (passed via `timeouts(...)`); there is **no** `deadlineMillis`
method on `GrpcClientConfig`.

```java
import com.tupago.sdk.core.auth.CredentialProvider;
import com.tupago.sdk.core.banxico.BanxicoMessage;
import com.tupago.sdk.core.config.TimeoutConfig;
import com.tupago.sdk.core.idempotency.IdempotencyKey;
import com.tupago.sdk.core.model.CreateQrRequest;
import com.tupago.sdk.core.result.PaymentOperationResult;
import com.tupago.sdk.grpc.GrpcClientConfig;
import com.tupago.sdk.grpc.GrpcTupagoClient;
import com.tupago.sdk.security.TlsConfig;

CredentialProvider credentials = () -> fetchTokenFromVault();   // never hard-code secrets

GrpcClientConfig config = GrpcClientConfig.builder()
        .target("tupago.example.com:9090")        // non-loopback requires TLS
        .credentials(credentials)
        .tls(TlsConfig.systemTrust())             // public CA trust
        .timeouts(TimeoutConfig.defaults()
                .withRequestMillis(10_000L)
                .withCreateOperationMillis(10_000L))
        .build();

try (GrpcTupagoClient client = GrpcTupagoClient.create(config)) {
    CreateQrRequest request = CreateQrRequest.of(
            1L,
            new BigDecimal("150.00"),
            "Pago de servicio",
            123456,
            "Juan Perez",
            "juan@example.com",
            1,
            24,
            IdempotencyKey.of("order-0001"));      // stable, caller-owned key

    PaymentOperationResult<BanxicoMessage> result =
            client.payments().createQr(request);
    // result is a core PaymentOperationResult<BanxicoMessage>; never a protobuf/gRPC type
}
```

REST and GraphQL use their own factories and `*ClientConfig` builders, then the same `TupagoClient`
contract (`payments()`/`clientes()`/`catalogos()`). The gRPC-`CreateQrRequest` (protobuf) from the
legacy client is **not** used by the modern API — use `com.tupago.sdk.core.model.CreateQrRequest`.

```java
// REST
import com.tupago.sdk.rest.RestClientConfig;
import com.tupago.sdk.rest.RestTupagoClient;

RestClientConfig restConfig = RestClientConfig.builder()
        .baseUri("https://tupago.example.com")
        .credentials(credentials)
        .build();
try (RestTupagoClient restClient = RestTupagoClient.create(restConfig)) {
    PaymentOperationResult<BanxicoMessage> restResult =
            restClient.payments().createQr(request);   // reuse the CreateQrRequest built above
}

// GraphQL
import com.tupago.sdk.graphql.GraphqlClientConfig;
import com.tupago.sdk.graphql.GraphqlTupagoClient;

GraphqlClientConfig gqlConfig = GraphqlClientConfig.builder()
        .baseUri("https://tupago.example.com")   // adapter appends the /graphql path
        .credentials(credentials)
        .build();
try (GraphqlTupagoClient gqlClient = GraphqlTupagoClient.create(gqlConfig)) {
    PaymentOperationResult<BanxicoMessage> gqlResult =
            gqlClient.payments().createQr(request);
}
```

---

## 8. TLS configuration (fail-closed)

- REST/GraphQL: the adapters accept `http` or `https` base URIs. HTTPS is **required by deployment
  policy** for any non-local production use; the SDK does **not** enforce this at the code level
  (a plaintext `http` origin is accepted by the client and must be rejected by deployment controls).
- gRPC: plaintext is permitted **only** to a loopback target (`127.0.0.1`, `::1`, `localhost`); any
  non-loopback target **requires** TLS or the client fails closed. There is **no** trust-all mode and
  **no** automatic downgrade.

```java
import com.tupago.sdk.security.TlsConfig;
TlsConfig tls = TlsConfig.systemTrust();                 // public CA
// TlsConfig.trustCertChainFile("/etc/tupago/ca.pem");   // explicit PEM
```

---

## 9. Authorization & secrets

- REST: `Authorization: Bearer <jwt>`. GraphQL: `Authorization: Bearer <jwt>`. gRPC: `authorization`
  metadata `Bearer <jwt>`.
- `CredentialProvider` is **runtime-supplied**, rotation-safe, and **never logged**. The SDK does not
  sign backend JWTs itself.
- No JWT, token, or secret is ever embedded in source, logs, or exception messages.

---

## 10. Distribution status

- **Internal distribution only.** Modules publish to Maven Local (`publishToMavenLocal`) and to any
  authorized internal Maven repository; consumers resolve the published coordinates above.
- **No public Maven Central release is performed by this program.** `.github/workflows/publish.yml`
  is a *controlled-release template*: it is triggered only on `v*` tags and performs **no publication**
  unless a release repository and credentials are explicitly authorized and wired into the Gradle
  publishing configuration. It is not auto-publishing. License: **Proprietary**.
- A real public release requires a separate, explicit authorization (repository, Sonatype credentials,
  PGP signing) and is out of scope for certification.

---

## 11. Limits & financial safety

- The contract is frozen; the SDK does not add/rename RPCs or fields.
- There is **no guaranteed successful financial outcome** if the network drops mid-create. The SDK
  preserves the `IdempotencyKey` and the unknown-outcome signal so the caller can reconcile.
- PostgreSQL/backend remains the financial authority; the SDK stores no financial state.
- No real Banxico/PSP calls are made by the SDK itself; financial operations require a reachable,
  authorized backend in your environment.
