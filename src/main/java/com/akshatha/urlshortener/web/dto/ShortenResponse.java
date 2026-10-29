package com.akshatha.urlshortener.web.dto;

import java.time.Instant;

/**
 * Returns the full shortUrl, not just the code. Making every client
 * concatenate a base URL is how you end up with three clients that disagree
 * about the trailing slash.
 */
public record ShortenResponse(
        String shortCode,
        String shortUrl,
        String longUrl,
        Instant createdAt,
        Instant expiresAt
) {
}
