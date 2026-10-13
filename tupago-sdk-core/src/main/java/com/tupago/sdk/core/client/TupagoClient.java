package com.tupago.sdk.core.client;

/**
 * Transport-neutral entry point for the TUPAGO3 SDK (SDK-01 contract).
 *
 * <p>Implementations are provided per transport in later phases (REST/GraphQL/gRPC). The core defines
 * only the capability groupings so that consumers depend on a stable, transport-agnostic surface and
 * never receive protobuf/GraphQL/HTTP-framework types.</p>
 */
public interface TupagoClient extends AutoCloseable {

    /** Payment operations (create + status). */
    PaymentOperations payments();

    /** Owned client-profile operations. */
    ClienteOperations clientes();

    /** Institution catalog operations. */
    CatalogoOperations catalogos();

    @Override
    void close();
}
