package com.akshatha.urlshortener.web.dto;

import com.akshatha.urlshortener.validation.SafeUrl;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/**
 * A record, not the entity. Never accept a JPA entity as a request body — it
 * would let a caller set id, clickCount and createdAt.
 *
 * The 2048 cap matches long_url VARCHAR(2048) in V1. If one moves, both move:
 * a DTO that accepts more than the column holds turns a user error into a
 * 500 at INSERT time.
 */
public record ShortenRequest(

        @Schema(example = "https://example.com/some/very/long/path")
        @NotBlank(message = "longUrl must not be blank")
        @Size(max = 2048, message = "longUrl must be at most 2048 characters")
        @SafeUrl
        String longUrl,

        /**
         * Optional vanity code. Restricted to the Base62 alphabet so a custom
         * alias can never contain a path separator, a dot, or anything else
         * that would change how the redirect route matches.
         */
        @Schema(example = "promo2026", description = "Optional custom alias, Base62 only, 4-10 chars")
        @Pattern(regexp = "^[0-9A-Za-z]{4,10}$",
                message = "customAlias must be 4-10 Base62 characters")
        String customAlias,

        @Schema(description = "Optional absolute expiry. Omit for a code that never expires.")
        @Future(message = "expiresAt must be in the future")
        Instant expiresAt
) {
}
