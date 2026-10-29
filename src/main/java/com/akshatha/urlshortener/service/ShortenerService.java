package com.akshatha.urlshortener.service;

import com.akshatha.urlshortener.codec.Base62Codec;
import com.akshatha.urlshortener.config.ShortenerProperties;
import com.akshatha.urlshortener.domain.UrlMapping;
import com.akshatha.urlshortener.exception.AliasAlreadyExistsException;
import com.akshatha.urlshortener.exception.ShortCodeExpiredException;
import com.akshatha.urlshortener.exception.ShortCodeNotFoundException;
import com.akshatha.urlshortener.repository.UrlMappingRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * All business logic. Controllers stay thin.
 *
 * Depends on IdAllocator, UrlCache and ClickCounter — all interfaces, never on
 * Redis or JPA types directly. That is what lets the M5 implementations slot
 * in without touching this file.
 */
@Service
public class ShortenerService {

    private static final Logger log = LoggerFactory.getLogger(ShortenerService.class);

    /**
     * With a monotonic counter allocator a collision should never happen. The
     * retry exists so that if it ever does, it is survivable — and so the
     * warning below tells us the allocator is broken.
     */
    private static final int MAX_COLLISION_RETRIES = 3;

    /** How long a losing thread waits for the stampede lock holder to populate. */
    private static final long STAMPEDE_BACKOFF_MILLIS = 25;

    /**
     * Single-segment paths that another mapping already answers, and which are
     * also valid Base62 strings — so a code of this shape would be accepted,
     * stored, and then never reachable, because the other mapping wins.
     *
     * "actuator" and "error" are the two that genuinely collide today
     * (/actuator is the discovery endpoint, /error is Spring's dispatch
     * target). The rest are reserved ahead of time: they are the obvious names
     * for routes this service may grow, and un-reserving a name later is easy
     * while taking one back from a user who is already linking to it is not.
     *
     * Paths containing '.', '-' or a second segment — /swagger-ui.html,
     * /v3/api-docs, /favicon.ico — cannot collide, because the route regex
     * only matches Base62.
     */
    private static final Set<String> RESERVED_CODES = Set.of(
            "actuator", "error", "admin", "login", "logout", "health",
            "metrics", "info", "docs", "api", "assets", "static", "public");

    private final UrlMappingRepository repository;
    private final IdAllocator idAllocator;
    private final UrlCache cache;
    private final ClickCounter clickCounter;
    private final ShortenerProperties properties;
    private final Clock clock;

    public ShortenerService(UrlMappingRepository repository,
                            IdAllocator idAllocator,
                            UrlCache cache,
                            ClickCounter clickCounter,
                            ShortenerProperties properties,
                            Clock clock) {
        this.repository = repository;
        this.idAllocator = idAllocator;
        this.cache = cache;
        this.clickCounter = clickCounter;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Allocate an id, Base62-encode it, persist, return the mapping.
     *
     * Deliberately not @Transactional around the retry loop: each attempt must
     * run in its own transaction, because a transaction that has already hit a
     * constraint violation is marked rollback-only and cannot be reused. Each
     * saveAndFlush below opens and commits its own.
     */
    public UrlMapping shorten(String longUrl, String customAlias, Instant expiresAt) {
        if (customAlias != null) {
            return saveWithAlias(longUrl, customAlias, expiresAt);
        }

        DataIntegrityViolationException last = null;
        for (int attempt = 1; attempt <= MAX_COLLISION_RETRIES; attempt++) {
            long id = idAllocator.nextId();
            String code = Base62Codec.encode(id);
            if (isReserved(code)) {
                // Astronomically unlikely, but a reserved code would be stored
                // happily and then shadowed by the other mapping forever. The
                // unique constraint cannot catch this one, so skip the id.
                log.warn("generated code {} is reserved, skipping that id", code);
                continue;
            }
            try {
                UrlMapping saved = repository.saveAndFlush(
                        new UrlMapping(id, code, longUrl, clock.instant(), expiresAt));
                // A previous negative cache entry for this code would otherwise
                // keep 404ing a code that now exists.
                cache.evict(code);
                return saved;
            } catch (DataIntegrityViolationException e) {
                last = e;
                log.warn("short code collision on {} (attempt {}/{}) — the id allocator "
                        + "should make this impossible, investigate",
                        code, attempt, MAX_COLLISION_RETRIES);
            }
        }
        throw new IllegalStateException(
                "could not allocate a unique short code after " + MAX_COLLISION_RETRIES
                        + " attempts", last);
    }

    /**
     * A taken alias is a 409, not a retry: silently handing back a different
     * code than the one the caller asked for is worse than refusing.
     */
    private UrlMapping saveWithAlias(String longUrl, String alias, Instant expiresAt) {
        if (isReserved(alias)) {
            throw new AliasAlreadyExistsException(alias);
        }
        if (repository.existsByShortCode(alias)) {
            throw new AliasAlreadyExistsException(alias);
        }
        try {
            UrlMapping saved = repository.saveAndFlush(
                    new UrlMapping(idAllocator.nextId(), alias, longUrl, clock.instant(), expiresAt));
            cache.evict(alias);
            return saved;
        } catch (DataIntegrityViolationException e) {
            // Lost the race between existsByShortCode and the insert.
            throw new AliasAlreadyExistsException(alias);
        }
    }

    /**
     * Cache-aside read path.
     *   hit             -> return
     *   known-absent    -> 404 without touching Postgres
     *   miss            -> DB, populate (positively or negatively), return
     */
    public String resolve(String shortCode) {
        CacheLookup lookup = cache.get(shortCode);

        if (lookup instanceof CacheLookup.Hit hit) {
            clickCounter.record(shortCode);
            return hit.longUrl();
        }
        if (lookup instanceof CacheLookup.KnownAbsent) {
            throw new ShortCodeNotFoundException(shortCode);
        }

        return loadAndCache(shortCode);
    }

    /**
     * Miss path, with stampede protection.
     *
     * When a hot code expires from the cache, every in-flight request for it
     * misses at the same instant and they all hit Postgres together. Only the
     * caller that wins a short SETNX lock does the read; the others back off
     * briefly and re-check the cache, which the winner has usually populated
     * by then.
     *
     * A loser that still misses falls through and reads the database anyway.
     * The lock is an optimisation, not a correctness barrier — blocking on it
     * would trade a load spike for a latency spike, which on the redirect path
     * is the worse of the two.
     */
    private String loadAndCache(String shortCode) {
        boolean holdsLock = cache.tryAcquireLoadLock(shortCode);
        try {
            if (!holdsLock) {
                sleepBriefly();
                CacheLookup second = cache.get(shortCode);
                if (second instanceof CacheLookup.Hit hit) {
                    clickCounter.record(shortCode);
                    return hit.longUrl();
                }
                if (second instanceof CacheLookup.KnownAbsent) {
                    throw new ShortCodeNotFoundException(shortCode);
                }
            }

            Optional<UrlMapping> found = repository.findByShortCode(shortCode);
            if (found.isEmpty()) {
                // Negative entry, short TTL: a flood of junk codes must not
                // reach Postgres on every request.
                cache.putAbsent(shortCode);
                throw new ShortCodeNotFoundException(shortCode);
            }

            UrlMapping mapping = found.get();
            Instant now = clock.instant();
            if (mapping.isExpiredAt(now)) {
                cache.putAbsent(shortCode);
                throw new ShortCodeExpiredException(shortCode);
            }

            if (mapping.getExpiresAt() == null) {
                cache.put(shortCode, mapping.getLongUrl());
            } else {
                // Never let the cached copy outlive the mapping itself.
                cache.put(shortCode, mapping.getLongUrl(),
                        Duration.between(now, mapping.getExpiresAt()));
            }

            clickCounter.record(shortCode);
            return mapping.getLongUrl();
        } finally {
            if (holdsLock) {
                cache.releaseLoadLock(shortCode);
            }
        }
    }

    /** Reserved names are compared case-insensitively; routes are not. */
    private static boolean isReserved(String code) {
        return RESERVED_CODES.contains(code.toLowerCase(Locale.ROOT));
    }

    /** The absolute URL handed back to clients. */
    public String shortUrlFor(String shortCode) {
        String base = properties.baseUrl();
        return base.endsWith("/") ? base + shortCode : base + "/" + shortCode;
    }

    private static void sleepBriefly() {
        try {
            Thread.sleep(STAMPEDE_BACKOFF_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
