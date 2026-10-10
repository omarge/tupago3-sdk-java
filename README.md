# TUPAGO3 SDK for Java

Blocking gRPC client for the TUPAGO3 `PaymentService`, generated **1:1 from the frozen
backend contract** (`payment.proto`, backend3 `22ad947`). The SDK is intended for
**controlled internal distribution only** — it is NOT published to Maven Central and carries
a **Proprietary** license (see `LICENSE`).

---

## 1. Description and limits

The SDK exposes the six RPCs of the backend `PaymentService`:

| RPC | Kind | Notes |
|-----|------|-------|
| `GetCliente` | read | profile + institution catalog |
| `GetCatalogo` | read | institution catalog by `clave_institucion` |
| `GetQrStatus` | read | Banxico status (JSON) for a QR |
| `GetTelefonicoStatus` | read | Banxico status (JSON) for a telefónico |
| `CreateQr` | **create** | **never auto-retried** |
| `CreateTelefonico` | **create** | **never auto-retried** |

Limits:

- The contract is frozen; the SDK does not add or rename RPCs/fields.
- `CreateQr` / `CreateTelefonico` are intentionally **non-retryable** (gate G4). A transient
  transport failure on a create is surfaced as `TupagoUnknownOutcomeException` and must NOT be
  blind-resubmitted; reuse the same `idempotency_key` or query status.
- There is **no guaranteed successful financial outcome** if the network drops mid-create. The
  SDK preserves the idempotency key and the unknown-outcome signal so the caller can reconcile.
- No real Banxico/PSP calls are made by the SDK itself; financial creates require a reachable,
  authorized backend in your environment.

## 2. Java requirements

- **Minimum: Java 21.** Bytecode is pinned to Java 21 (`options.release = 21`).
- Certified on **Java 21 and Java 25** runtimes.
- Build tool: **Gradle 9.2.1** (gRPC 1.84.1, Protobuf 4.36.2).

## 3. Install — Gradle

```gradle
// settings.gradle / repositories: mavenLocal() must be present for internal distribution
repositories {
    mavenLocal()
    mavenCentral()
}

dependencies {
    implementation 'com.tupago:tupago3-sdk-java:0.1.0'
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
  <artifactId>tupago3-sdk-java</artifactId>
  <version>0.1.0</version>
</dependency>
```

## 5. TLS configuration (fail-closed)

Plaintext is permitted **only** to a loopback target (`127.0.0.1`, `::1`, `localhost`). Any
non-loopback target **requires** TLS or the client fails closed (gate G8).

```java
import com.tupago.sdk.security.TlsConfig;

// Public CA (system trust store):
TlsConfig tls = TlsConfig.systemTrust();

// Or an explicit PEM file / in-memory PEM:
// TlsConfig tls = TlsConfig.trustCertChainFile("/etc/tupago/ca.pem");
// TlsConfig tls = TlsConfig.trustCertChainPem(pemBytes);

TupagoPaymentClient client = TupagoPaymentClient.builder()
    .target("tupago.example.com:9090")
    .credentials(credentials)
    .tls(tls)
    .deadlineMillis(10_000)
    .build();
```

There is **no TLS bypass**. To a loopback target you may omit `.tls(...)` and it will use a
plaintext channel (local development only).

## 6. CredentialProvider integration

The SDK **never embeds credentials** (gate G3). You supply a `CredentialProvider` that returns
a current Bearer JWT per call:

```java
import com.tupago.sdk.auth.CredentialProvider;

CredentialProvider credentials = () -> {
    // Obtain a fresh JWT from your OAuth2 client-credentials flow / secret manager.
    // NEVER hard-code the secret here.
    return fetchTokenFromVault();
};
```

The token is attached as an `Authorization: Bearer <jwt>` header on every RPC via an interceptor.
Returning `null` yields `UNAUTHENTICATED` on the next call.

## 7. Secure `GetCatalogo` example

```java
import com.tupago.sdk.client.TupagoPaymentClient;
import com.tupago.sdk.security.TlsConfig;
import com.tupago.sdk.grpc.v1.CatalogoInstitucion;

try (TupagoPaymentClient client = TupagoPaymentClient.builder()
        .target("127.0.0.1:9090")                 // loopback -> plaintext allowed
        .credentials(credentials)
        .deadlineMillis(5_000)
        .build()) {

    CatalogoInstitucion cat = client.getCatalogo("301");
    System.out.println(cat.getNombreCorto());
}
```

## 8. `GetCliente`, `GetQrStatus`, `GetTelefonicoStatus`

```java
import com.tupago.sdk.grpc.v1.ClientePerfil;
import com.tupago.sdk.client.JsonResponseView;

ClientePerfil perfil = client.getCliente(1L);
System.out.println(perfil.getCliente().getNombre());

// QR and Telefonico status return a raw Banxico JSON string.
String idc = "...";
long idCuenta = 1L;

var qrStatus = new JsonResponseView(client.getQrStatus(idc, idCuenta));
System.out.println(qrStatus.json());                 // raw JSON, lossless

var telStatus = new JsonResponseView(client.getTelefonicoStatus(idc, idCuenta));
var node = telStatus.parsed();                       // parsed tree (lazily)
// Or bind to your own DTO:  MyDto dto = telStatus.as(MyDto.class);
```

## 9. `CreateQr` / `CreateTelefonico` with explicit idempotency_key

Always pass a **stable, unique** `idempotency_key` you control. The server dedupes identical keys.

```java
import com.tupago.sdk.grpc.v1.CreateQrRequest;
import com.tupago.sdk.grpc.v1.CreateTelefonicoRequest;

CreateQrRequest qr = CreateQrRequest.newBuilder()
        .setIdCuenta(1L)
        .setMonto(150.00)
        .setConcepto("Pago de servicio")
        .setReferenciaNumerica(123456)
        .setNombreCliente("Juan Perez")
        .setEmail("juan@example.com")
        .setTipoPago(1)
        .setVigencia(24)
        .setIdempotencyKey("order-0001-attempt-1")   // stable, never reused blindly
        .build();
var qrResp = new JsonResponseView(client.createQr(qr));

CreateTelefonicoRequest tel = CreateTelefonicoRequest.newBuilder()
        .setKey("...")
        .setMonto(150.00)
        .setConcepto("Pago telefónico")
        .setReferenciaNumerica(123456)
        .setNumeroCelular("5512345678")
        .setVigencia(24)
        .setIdempotencyKey("order-0002-attempt-1")
        .build();
var telResp = new JsonResponseView(client.createTelefonico(tel));
```

## 10. Handling `TupagoUnknownOutcomeException`

A transient transport error on a create means the server *may or may not* have persisted the
payment. The SDK raises `TupagoUnknownOutcomeException` (non-retryable) so you cannot blind-resubmit:

```java
try {
    client.createQr(qr);
} catch (TupagoUnknownOutcomeException ex) {
    // DO NOT resubmit a fresh request.
    // Reuse the same idempotency_key (server dedupes) or query status:
    var status = client.getQrStatus(qr.getIdcOrThrowEquivalent(), qr.getIdCuenta());
    // ... reconcile from the status JSON
}
```

(Use the same `idc` / `idCuenta` you used originally; `CreateQrRequest` carries `id_cuenta` only,
so track `idc` out-of-band for the status query.)

## 11. Handling `TUPAGO_PENDING_RECONCILIATION`

When the backend returns `ABORTED` with the description containing `TUPAGO_PENDING_RECONCILIATION`
(this is a non-transient state), the SDK raises `TupagoAbortedException` with
`isPendingReconciliation() == true` (gate G6).

```java
catch (TupagoAbortedException ex) {
    if (ex.isPendingReconciliation()) {
        // Reconcile by querying status with the SAME idempotency key. Do not resubmit a new create.
        var status = client.getQrStatus(idc, idCuenta);
    }
}
```

## 12. NoRetry policy for Create operations

`CreateQr` / `CreateTelefonico` use `NoRetryPolicy` internally (gate G4). The SDK performs
**exactly one** network attempt per call. Reads use a transient-only `BackoffRetryPolicy(3)`
(`UNAVAILABLE` / `DEADLINE_EXCEEDED` only).

## 13. Exception table

All exceptions extend `TupagoException` and carry the original gRPC `Status` (`code()`,
`description()`, `status()`), plus `isRetryable()` / `isPendingReconciliation()`.

| gRPC status | SDK exception | Retryable? | Pending reconciliation? |
|-------------|---------------|-----------|--------------------------|
| `UNAUTHENTICATED` | `TupagoUnauthenticatedException` | no | no |
| `PERMISSION_DENIED` | `TupagoPermissionDeniedException` | no | no |
| `INVALID_ARGUMENT` | `TupagoInvalidArgumentException` | no | no |
| `ALREADY_EXISTS` | `TupagoAlreadyExistsException` | no | no |
| `FAILED_PRECONDITION` | `TupagoFailedPreconditionException` | no | no |
| `NOT_FOUND` | `TupagoNotFoundException` | no | no |
| `ABORTED` (`TUPAGO_PENDING_RECONCILIATION`) | `TupagoAbortedException` | no | **yes** |
| `UNAVAILABLE` / `DEADLINE_EXCEEDED` (read) | `TupagoTransientException` | **yes** | no |
| `INTERNAL` | `TupagoInternalException` | no | no |
| create transport failure | `TupagoUnknownOutcomeException` | no | no |
| any other | `TupagoException` | no | no |

## 14. Deadlines and resource release

- Set a per-request deadline via `.deadlineMillis(...)`. The blocking stub applies it with
  `withDeadlineAfter`.
- `TupagoPaymentClient` is `AutoCloseable`. Always use try-with-resources (shown above) so the
  underlying `ManagedChannel` is `shutdownNow()` cleanly. Failure to close can leak threads /
  connections.

```java
try (TupagoPaymentClient client = TupagoPaymentClient.builder()
        .target("127.0.0.1:9090").credentials(credentials).build()) {
    // ... use client
} // channel closed here
```

## 15. No JWT / secrets in logs or errors

The SDK does **not** log JWTs, tokens, or secret material. Exception messages contain only the
gRPC status code and description (e.g. `UNAUTHENTICATED: ...`). Do not wrap SDK exceptions in a way
that prints the bearer token. Keep `CredentialProvider` implementations secret-free.

## 16. Java 21 / 25 compatibility

- Bytecode is emitted at Java 21 (`options.release = 21`); the artifact runs on any JVM >= 21.
- Certified on Java 21 and Java 25. The Gradle build is parameterized:
  `./gradlew clean build -PjavaVersion=21` and `-PjavaVersion=25`.

---

## Distribution status

- **Internal distribution only.** Published to Maven Local (`publishToMavenLocal`) is supported.
- **Maven Central publishing is BLOCKED** until explicit authorization, credentials, and Central
  compliance are provided.
- License: **Proprietary** (see `LICENSE`).

No real financial operations are performed by the SDK or its test suites.
