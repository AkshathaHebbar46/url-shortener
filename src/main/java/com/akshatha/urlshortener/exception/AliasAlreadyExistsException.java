package com.akshatha.urlshortener.exception;

/**
 * A requested custom alias is taken. Mapped to 409 Conflict, not retried:
 * retrying would hand the caller a different code than the one they asked
 * for, which is worse than telling them no.
 */
public class AliasAlreadyExistsException extends RuntimeException {

    private final String alias;

    public AliasAlreadyExistsException(String alias) {
        super("alias already in use: " + alias);
        this.alias = alias;
    }

    public String getAlias() {
        return alias;
    }
}
