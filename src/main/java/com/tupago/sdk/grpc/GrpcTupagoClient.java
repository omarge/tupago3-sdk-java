package com.tupago.sdk.grpc;

import com.tupago.sdk.core.client.CatalogoOperations;
import com.tupago.sdk.core.client.ClienteOperations;
import com.tupago.sdk.core.client.PaymentOperations;
import com.tupago.sdk.core.client.TupagoClient;
import com.tupago.sdk.grpc.internal.GrpcTransport;

/**
 * gRPC transport entry point for the TUPAGO3 Java SDK (SDK-04).
 *
 * <p>Reconciles the historical root gRPC artifact with the transport-neutral SDK-01 core: this class
 * implements the core {@link TupagoClient} contract, so consumers receive only core types and never see
 * generated protobuf stubs, {@code io.grpc} types, or the legacy {@code TupagoException} hierarchy. Build via
 * {@link #create(GrpcClientConfig)}.</p>
 */
public final class GrpcTupagoClient implements TupagoClient {

    private final GrpcTransport transport;
    private final PaymentOperations payments;
    private final ClienteOperations clientes;
    private final CatalogoOperations catalogos;

    private GrpcTupagoClient(GrpcClientConfig config) {
        this.transport = new GrpcTransport(config);
        this.payments = new GrpcPaymentOperations(transport);
        this.clientes = new GrpcClienteOperations(transport);
        this.catalogos = new GrpcCatalogoOperations(transport);
    }

    /** Create a gRPC-backed client from the given configuration. */
    public static GrpcTupagoClient create(GrpcClientConfig config) {
        return new GrpcTupagoClient(config);
    }

    @Override
    public PaymentOperations payments() {
        return payments;
    }

    @Override
    public ClienteOperations clientes() {
        return clientes;
    }

    @Override
    public CatalogoOperations catalogos() {
        return catalogos;
    }

    @Override
    public void close() {
        transport.close();
    }
}
