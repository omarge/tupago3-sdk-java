package com.tupago.sdk.core.client;

import com.tupago.sdk.core.banxico.BanxicoMessage;
import com.tupago.sdk.core.model.CreateQrRequest;
import com.tupago.sdk.core.model.CreateTelefonicoRequest;
import com.tupago.sdk.core.model.GetQrStatusRequest;
import com.tupago.sdk.core.model.GetTelefonicoStatusRequest;
import com.tupago.sdk.core.result.PaymentOperationResult;

/** Payment capabilities C1-C4 (contract; implemented per transport in later phases). */
public interface PaymentOperations {

    PaymentOperationResult<BanxicoMessage> createQr(CreateQrRequest request);

    PaymentOperationResult<BanxicoMessage> createTelefonico(CreateTelefonicoRequest request);

    PaymentOperationResult<BanxicoMessage> getQrStatus(GetQrStatusRequest request);

    PaymentOperationResult<BanxicoMessage> getTelefonicoStatus(GetTelefonicoStatusRequest request);
}
