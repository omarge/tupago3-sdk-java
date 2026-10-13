package com.tupago.sdk.rest;

import com.tupago.sdk.core.client.CatalogoOperations;
import com.tupago.sdk.core.client.ClienteOperations;
import com.tupago.sdk.core.client.PaymentOperations;
import com.tupago.sdk.core.client.TupagoClient;
import com.tupago.sdk.rest.internal.RestTransport;

/**
 * REST transport entry point for the TUPAGO3 Java SDK (SDK-02).
 *
 * <p>Implements the transport-neutral {@link TupagoClient} contract; all returned capability objects are
 * core types, so consumers never see HTTP-framework, Jackson, or {@code java.net.http} types. Build via
 * {@link #create(RestClientConfig)}.</p>
 */
public final class RestTupagoClient implements TupagoClient {

    private final RestTransport transport;
    private final RestPaymentOperations payments;
    private final RestClienteOperations clientes;
    private final RestCatalogoOperations catalogos;

    private RestTupagoClient(RestClientConfig config) {
        this.transport = new RestTransport(config);
        this.payments = new RestPaymentOperations(transport, config.objectMapper());
        this.clientes = new RestClienteOperations(transport, config.objectMapper());
        this.catalogos = new RestCatalogoOperations(transport, config.objectMapper());
    }

    /** Create a REST-backed client from the given configuration. */
    public static RestTupagoClient create(RestClientConfig config) {
        return new RestTupagoClient(config);
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
