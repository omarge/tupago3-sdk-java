package com.tupago.sdk.core.model;

/**
 * Transport-neutral client profile (mirrors the backend {@code ClientePerfil} shape:
 * cliente {id, nombre, claveInstitucion, key} + catalogoInstitucion).
 */
public record ClientePerfil(
        Long id,
        String nombre,
        String claveInstitucion,
        String key,
        CatalogoInstitucion catalogoInstitucion) {}
