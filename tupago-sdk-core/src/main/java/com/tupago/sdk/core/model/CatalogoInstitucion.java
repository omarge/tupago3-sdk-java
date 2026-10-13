package com.tupago.sdk.core.model;

/**
 * Transport-neutral institution catalog entry (mirrors the backend {@code CatalogoInstitucion}
 * shape: id, claveInstitucion, nombreCorto).
 */
public record CatalogoInstitucion(
        Long id,
        String claveInstitucion,
        String nombreCorto) {}
