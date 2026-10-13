package com.tupago.sdk.core.client;

import com.tupago.sdk.core.model.ClientePerfil;
import com.tupago.sdk.core.model.GetClienteRequest;
import com.tupago.sdk.core.result.PaymentOperationResult;

/** Client-profile capability C5 (contract; implemented per transport in later phases). */
public interface ClienteOperations {
    PaymentOperationResult<ClientePerfil> getCliente(GetClienteRequest request);
}
