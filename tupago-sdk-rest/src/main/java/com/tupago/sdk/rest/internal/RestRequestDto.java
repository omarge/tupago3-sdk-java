package com.tupago.sdk.rest.internal;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.tupago.sdk.core.banxico.BanxicoMessage;
import com.tupago.sdk.core.model.CreateQrRequest;
import com.tupago.sdk.core.model.CreateTelefonicoRequest;
import com.tupago.sdk.core.model.GetQrStatusRequest;
import com.tupago.sdk.core.model.GetTelefonicoStatusRequest;
import java.math.BigDecimal;

/** REST request DTOs (mirror backend banxico/Cliente/Catalogo request shapes at anchor bbf06c02). */
@JsonInclude(JsonInclude.Include.NON_NULL)
public final class RestRequestDto {

    private RestRequestDto() {
    }

    public record CreateQrRequestDto(
            Long idCuenta,
            BigDecimal monto,
            String concepto,
            Integer referenciaNumerica,
            String nombreCliente,
            String email,
            Integer tipoPago,
            Integer vigencia) {
        public static CreateQrRequestDto from(CreateQrRequest r) {
            return new CreateQrRequestDto(
                    r.idCuenta(),
                    r.monto(),
                    r.concepto(),
                    r.referenciaNumerica(),
                    r.nombreCliente(),
                    r.email(),
                    r.tipoPago(),
                    r.vigencia());
        }
    }

    public record CreateTelefonicoRequestDto(
            String key,
            BigDecimal monto,
            String concepto,
            Integer referenciaNumerica,
            Long numeroCelular,
            Integer vigencia) {
        public static CreateTelefonicoRequestDto from(CreateTelefonicoRequest r) {
            Long cel = null;
            if (r.numeroCelular() != null && !r.numeroCelular().isBlank()) {
                try {
                    cel = Long.parseLong(r.numeroCelular().trim());
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException(
                            "CreateTelefonicoRequest.numeroCelular must be a numeric string, got: "
                                    + r.numeroCelular(), e);
                }
            }
            return new CreateTelefonicoRequestDto(
                    r.key(),
                    r.monto(),
                    r.concepto(),
                    r.referenciaNumerica(),
                    cel,
                    r.vigencia());
        }
    }

    public record QrStatusRequestDto(
            String dv,
            String idc,
            String certificado,
            Integer tpg1,
            Integer npg1,
            Long idCuenta) {
        public static QrStatusRequestDto from(GetQrStatusRequest r) {
            return new QrStatusRequestDto(null, r.idc(), null, null, null, r.idCuenta());
        }
    }

    public record TelefonicoStatusRequestDto(Long idCuenta, String idMsjdeCobro) {
        public static TelefonicoStatusRequestDto from(GetTelefonicoStatusRequest r) {
            return new TelefonicoStatusRequestDto(r.idCuenta(), r.idc());
        }
    }
}
