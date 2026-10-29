package com.akshatha.urlshortener.exception;

public class ShortCodeNotFoundException extends RuntimeException {

    private final String shortCode;

    public ShortCodeNotFoundException(String shortCode) {
        super("no mapping for short code " + shortCode);
        this.shortCode = shortCode;
    }

    public String getShortCode() {
        return shortCode;
    }
}
