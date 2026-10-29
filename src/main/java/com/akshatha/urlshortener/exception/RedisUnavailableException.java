package com.akshatha.urlshortener.exception;

/**
 * Raised on the write path when the id allocator cannot reach Redis.
 *
 * Mapped to 503. The alternative — quietly falling back to the Postgres
 * sequence — would hand out ids from a counter that has no idea what Redis has
 * already issued, so the two sources would overlap and break the uniqueness
 * guarantee that is the entire point of the block allocator. See
 * docs/decisions.md.
 */
public class RedisUnavailableException extends RuntimeException {

    public RedisUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
