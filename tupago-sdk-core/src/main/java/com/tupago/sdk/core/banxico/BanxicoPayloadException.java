package com.tupago.sdk.core.banxico;

/** Thrown when a Banxico payload string cannot be parsed. */
public class BanxicoPayloadException extends RuntimeException {
    public BanxicoPayloadException(String message, Throwable cause) {
        super(message, cause);
    }
}
