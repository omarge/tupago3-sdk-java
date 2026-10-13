package com.tupago.sdk.graphql.internal;

import com.tupago.sdk.core.model.CreateQrRequest;
import com.tupago.sdk.core.model.CreateTelefonicoRequest;
import java.math.BigDecimal;

/**
 * GraphQL variable payloads (SDK-03). These map the transport-neutral core request models onto the
 * backend {@code PagoQrInput} / {@code PagoTelefonicoInput} GraphQL input shapes. All fields are sent
 * through the operation's {@code variables} map — never interpolated into the query source.
 */
public final class GraphqlRequestDto {

    private GraphqlRequestDto() {
    }

    public record PagoQrInput(
            Long idCuenta,
            BigDecimal monto,
            String concepto,
            Integer referenciaNumerica,
            String nombreCliente,
            String email,
            Integer tipoPago,
            Integer vigencia) {

        public static PagoQrInput from(CreateQrRequest r) {
            return new PagoQrInput(r.idCuenta(), r.monto(), r.concepto(), r.referenciaNumerica(),
                    r.nombreCliente(), r.email(), r.tipoPago(), r.vigencia());
        }
    }

    public record PagoTelefonicoInput(
            String key,
            BigDecimal monto,
            String concepto,
            Integer referenciaNumerica,
            String numeroCelular,
            Integer vigencia) {

        public static PagoTelefonicoInput from(CreateTelefonicoRequest r) {
            return new PagoTelefonicoInput(r.key(), r.monto(), r.concepto(), r.referenciaNumerica(),
                    r.numeroCelular(), r.vigencia());
        }
    }
}
