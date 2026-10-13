package com.tupago.sdk.graphql;

import com.tupago.sdk.core.client.CatalogoOperations;
import com.tupago.sdk.core.client.ClienteOperations;
import com.tupago.sdk.core.client.PaymentOperations;
import com.tupago.sdk.core.client.TupagoClient;
import com.tupago.sdk.graphql.internal.GraphqlTransport;

/**
 * GraphQL transport entry point for the TUPAGO3 Java SDK (SDK-03).
 *
 * <p>Implements the transport-neutral {@link TupagoClient} contract; all returned capability objects are
 * core types, so consumers never see HTTP-framework, Jackson, or {@code java.net.http} types. Build via
 * {@link #create(GraphqlClientConfig)}.</p>
 */
public final class GraphqlTupagoClient implements TupagoClient {

    private final GraphqlTransport transport;
    private final GraphqlPaymentOperations payments;
    private final GraphqlClienteOperations clientes;
    private final GraphqlCatalogoOperations catalogos;

    private GraphqlTupagoClient(GraphqlClientConfig config) {
        this.transport = new GraphqlTransport(config);
        this.payments = new GraphqlPaymentOperations(transport, config.objectMapper());
        this.clientes = new GraphqlClienteOperations(transport);
        this.catalogos = new GraphqlCatalogoOperations(transport);
    }

    /** Create a GraphQL-backed client from the given configuration. */
    public static GraphqlTupagoClient create(GraphqlClientConfig config) {
        return new GraphqlTupagoClient(config);
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
        // The JDK HttpClient has no close(); nothing to release for the default client.
    }
}
