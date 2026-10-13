package com.tupago.sdk.core.client;

import com.tupago.sdk.core.model.CatalogoInstitucion;
import com.tupago.sdk.core.model.GetCatalogoRequest;
import com.tupago.sdk.core.result.PaymentOperationResult;

/** Catalog capability C6 (contract; implemented per transport in later phases). */
public interface CatalogoOperations {
    PaymentOperationResult<CatalogoInstitucion> getCatalogo(GetCatalogoRequest request);
}
