package com.akshatha.urlshortener.exception;

/**
 * Mapped to 410 Gone: the code was real, its lifetime is over, and no retry
 * will bring it back. 404 is defensible too — see docs/decisions.md.
 */
public class ShortCodeExpiredException extends RuntimeException {

    private final String shortCode;

    public ShortCodeExpiredException(String shortCode) {
        super("short code " + shortCode + " has expired");
        this.shortCode = shortCode;
    }

    public String getShortCode() {
        return shortCode;
    }
}
