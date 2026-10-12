package com.tupago.sdk.client;

import com.tupago.sdk.auth.CredentialProvider;
import com.tupago.sdk.error.TupagoNotFoundException;
import com.tupago.sdk.grpc.v1.ClientePerfil;
import com.tupago.sdk.security.TlsConfig;
import java.io.FileWriter;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * F03-R4 differential principal-isolation diagnostic. Exercises I01-I10 against the isolated
 * cert-env backend3. Logs ONLY logical token (A/B), queried id, returned id / outcome, and whether
 * the channel/client was new or reused. Never logs token values or Authorization headers.
 *
 * Run with -Dit.target -Dit.ca -Dit.tls -Dit.jwtA -Dit.jwtB. Optionally -Dit.mode to scope.
 */
class F03R4DiagnosticTest {

    private static String target;
    private static TlsConfig tls;
    private static String jwtA;
    private static String jwtB;

    @BeforeAll
    static void setup() {
        Assumptions.assumeTrue(Boolean.parseBoolean(System.getProperty("it.enabled", "false")), "off");
        target = System.getProperty("it.target", "localhost:29091");
        String ca = System.getProperty("it.ca", "/tmp/tupago3-cert-grpc-ca.pem");
        tls = ca.isBlank() ? TlsConfig.systemTrust() : TlsConfig.trustCertChainFile(ca);
        jwtA = readToken(System.getProperty("it.jwtAFile"), System.getProperty("it.jwtA", ""));
        jwtB = readToken(System.getProperty("it.jwtBFile"), System.getProperty("it.jwtB", ""));
        Assumptions.assumeTrue(!jwtA.isBlank() && !jwtB.isBlank(), "need both jwtA and jwtB");
    }

    private static String readToken(String file, String fallback) {
        if (file != null && !file.isBlank()) {
            try {
                return new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(file)), StandardCharsets.UTF_8).trim();
            } catch (Exception e) {
                // fall through to fallback
            }
        }
        return fallback == null ? "" : fallback;
    }

    private TupagoPaymentClient clientFor(String tok) {
        return TupagoPaymentClient.builder()
                .target(target).credentials((CredentialProvider) () -> tok).tls(tls).build();
    }

    /** Returns a short outcome: "OK:<id>" / "NF" / "EX:<code>". */
    private String probe(TupagoPaymentClient c, long id) {
        try {
            ClientePerfil p = c.getCliente(id);
            return "OK:" + (p.getCliente() != null ? p.getCliente().getId() : "?");
        } catch (TupagoNotFoundException nfe) {
            return "NF";
        } catch (Exception e) {
            return "EX:" + e.getClass().getSimpleName();
        }
    }

    private void log(String line) {
        try (FileWriter w = new FileWriter("/tmp/f03r4_results.log", true)) {
            w.write(line + "\n");
        } catch (Exception ignore) {}
    }

    private void header(String s) {
        log("### " + s);
    }

    @Test
    void I01_newChannel_A() {
        header("I01 new-channel tokenA 910001 & 910002");
        TupagoPaymentClient a = clientFor(jwtA);
        log("I01 A 910001 -> " + probe(a, 910001) + " [newClient]");
        log("I01 A 910002 -> " + probe(a, 910002) + " [reusedClientSameConn]");
    }

    @Test
    void I02_newChannel_B() {
        header("I02 new-channel tokenB 910002 & 910001");
        TupagoPaymentClient b = clientFor(jwtB);
        log("I02 B 910002 -> " + probe(b, 910002) + " [newClient]");
        log("I02 B 910001 -> " + probe(b, 910001) + " [reusedClientSameConn]");
    }

    @Test
    void I03_reusedConn_A_then_B() {
        header("I03 reused-conn tokenA then tokenB");
        TupagoPaymentClient a = clientFor(jwtA);
        log("I03 A 910001 -> " + probe(a, 910001) + " [clientA]");
        TupagoPaymentClient b = clientFor(jwtB); // separate client, SHARED pooled connection
        log("I03 B 910002 -> " + probe(b, 910002) + " [clientB-sharedConn]");
        log("I03 B 910001 -> " + probe(b, 910001) + " [clientB-sharedConn]");
    }

    @Test
    void I04_reusedConn_B_then_A() {
        header("I04 reused-conn tokenB then tokenA");
        TupagoPaymentClient b = clientFor(jwtB);
        log("I04 B 910002 -> " + probe(b, 910002) + " [clientB]");
        TupagoPaymentClient a = clientFor(jwtA);
        log("I04 A 910001 -> " + probe(a, 910001) + " [clientA-sharedConn]");
        log("I04 A 910002 -> " + probe(a, 910002) + " [clientA-sharedConn]");
    }

    @Test
    void I05_alternating_ABAB() {
        header("I05 alternating A B A B (shared conn)");
        TupagoPaymentClient a = clientFor(jwtA);
        TupagoPaymentClient b = clientFor(jwtB);
        log("I05 A 910001 -> " + probe(a, 910001));
        log("I05 B 910002 -> " + probe(b, 910002));
        log("I05 A 910001 -> " + probe(a, 910001));
        log("I05 B 910002 -> " + probe(b, 910002));
    }

    @Test
    void I06_twoChannels_interleaved() {
        header("I06 two channels A/B interleaved");
        TupagoPaymentClient a = clientFor(jwtA);
        TupagoPaymentClient b = clientFor(jwtB);
        log("I06 A 910001 -> " + probe(a, 910001));
        log("I06 B 910001 -> " + probe(b, 910001));
        log("I06 A 910002 -> " + probe(a, 910002));
        log("I06 B 910002 -> " + probe(b, 910002));
    }

    @Test
    void I07_concurrent_AB() {
        header("I07 concurrent A and B");
        TupagoPaymentClient a = clientFor(jwtA);
        TupagoPaymentClient b = clientFor(jwtB);
        java.util.concurrent.atomic.AtomicReference<String> ra = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<String> rb = new java.util.concurrent.atomic.AtomicReference<>();
        Thread ta = new Thread(() -> { for (int i = 0; i < 5; i++) ra.set("A910001=" + probe(a, 910001)); });
        Thread tb = new Thread(() -> { for (int i = 0; i < 5; i++) rb.set("B910002=" + probe(b, 910002)); });
        ta.start(); tb.start();
        try { ta.join(); tb.join(); } catch (InterruptedException ignore) {}
        log("I07 " + ra.get());
        log("I07 " + rb.get());
    }

    @Test
    void I10_oneClient_switchCredentialsPerCall() {
        header("I10 ONE client, credentials switched per call (same connection)");
        AtomicReference<String> cur = new AtomicReference<>(jwtB);
        TupagoPaymentClient c = TupagoPaymentClient.builder()
                .target(target).credentials((CredentialProvider) cur::get).tls(tls).build();
        // First call as B, then flip to A, then back to B.
        log("I10 asB 910002 -> " + probe(c, 910002) + " [creds=B]");
        cur.set(jwtA);
        log("I10 asA 910001 -> " + probe(c, 910001) + " [creds=A]");
        cur.set(jwtB);
        log("I10 asB 910002 -> " + probe(c, 910002) + " [creds=B again]");
    }
}
