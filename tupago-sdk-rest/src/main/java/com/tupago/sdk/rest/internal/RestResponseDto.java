package com.tupago.sdk.rest.internal;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** REST response DTOs (deserialization shapes for the backend contract at anchor bbf06c02). */
@JsonIgnoreProperties(ignoreUnknown = true)
public final class RestResponseDto {

    private RestResponseDto() {
    }

    /** Create-QR success body: `{ mensaje, emisor, banco }` where `mensaje` is the raw Banxico payload. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CreateQrResponse(@JsonProperty("mensaje") String mensaje,
                            @JsonProperty("emisor") String emisor,
                            @JsonProperty("banco") String banco) {
    }

    /** Create-Telefonico success body: `{ detalleResultado, edoPet }` where `detalleResultado` is the raw Banxico JSON string. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CreateTelefonicoResponse(@JsonProperty("detalleResultado") String detalleResultado,
                                    @JsonProperty("edoPet") int edoPet) {
    }

    /** Owned client profile (subset of backend ClienteDTO that the owner-scoped GET returns). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ClienteDto(Long id, String key, String nombre, String claveInstitucion) {
    }

    /** Institution catalog entry (mirrors backend CatalogoInstitucionDTO). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CatalogoInstitucionDto(Long id, String claveInstitucion, String nombreCorto) {
    }

    /** RFC-7807 Problem Detail (used for error responses and the 202 pending-reconciliation body). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ProblemDetail(@JsonProperty("type") String type,
                         @JsonProperty("title") String title,
                         @JsonProperty("detail") String detail,
                         @JsonProperty("status") Integer status) {
    }
}
