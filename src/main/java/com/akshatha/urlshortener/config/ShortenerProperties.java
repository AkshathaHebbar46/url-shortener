package com.akshatha.urlshortener.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Typed binding for the `shortener.*` block in application.yml.
 *
 * Immutable records, validated at startup: a bad deploy config fails the
 * context refresh with a readable message instead of surfacing as an NPE at
 * 3am. Spring binds kebab-case yml keys to camelCase components.
 */
@Validated
@ConfigurationProperties(prefix = "shortener")
public record ShortenerProperties(

        /** Absolute base used to build the returned shortUrl, e.g. https://sho.rt */
        @NotBlank String baseUrl,

        /** Expected code length; drives the sequence seed and the codec tests. */
        @Min(4) int codeLength,

        /** How many ids an instance claims from Redis per INCRBY (M5). */
        @Positive int idBlockSize,

        /** Which IdAllocator to wire. SEQUENCE is the M4 control group. */
        @NotNull Allocator idAllocator,

        @NotNull @Valid Cache cache,

        @NotNull @Valid RateLimit rateLimit
) {

    public enum Allocator {
        /** One DB round-trip per shorten(). Correct, slow, simple. */
        SEQUENCE,
        /** One Redis round-trip per idBlockSize shortens. */
        REDIS
    }

    public record Cache(

            /**
             * false wires NoOpUrlCache, so every redirect goes to Postgres.
             * That is the configuration M4 measures — flip this property to
             * re-run the baseline rather than reverting code.
             */
            boolean enabled,

            /** TTL for a real cached URL. */
            @NotNull Duration ttl,

            /**
             * TTL for a known-absent marker. Much shorter than ttl: a negative
             * entry is a guess about something that does not exist yet, and a
             * code created a second later must not stay invisible for a day.
             */
            @NotNull Duration negativeTtl,

            /** How long the anti-stampede SETNX lock is held. */
            @NotNull Duration stampedeLockTtl,

            /** How often buffered click counts are flushed to Postgres. */
            @NotNull Duration clickFlushInterval
    ) {
    }

    public record RateLimit(

            boolean enabled,

            /** Writes are expensive and abusable. */
            @Positive int shortenPerMinute,

            /** Redirects are cheap and legitimately bursty. */
            @Positive int redirectPerMinute,

            /**
             * What to do when Redis is unreachable. true serves everything
             * unlimited; false rejects everything. See docs/decisions.md.
             */
            boolean failOpen
    ) {
    }
}
