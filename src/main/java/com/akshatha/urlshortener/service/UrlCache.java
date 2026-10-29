package com.akshatha.urlshortener.service;

/**
 * The cache seam. ShortenerService talks to this, never to RedisTemplate.
 *
 * Two implementations:
 *   M2 — NoOpUrlCache. Every lookup misses. This is what M4 benchmarks.
 *   M5 — RedisUrlCache.
 *
 * The no-op is not a placeholder, it is the control group: flipping
 * shortener.cache.enabled re-runs the baseline without touching code.
 *
 * Implementations must never throw. A cache that throws on a degraded
 * dependency turns a Redis outage into a full outage.
 */
public interface UrlCache {

    CacheLookup get(String shortCode);

    /** Cache a real URL, with the configured positive TTL. */
    void put(String shortCode, String longUrl);

    /**
     * Cache a real URL for at most {@code maxTtl}. Used when the mapping has an
     * expiry: a cached entry that outlives its row would keep serving a dead
     * code, so the effective TTL is min(configured, time left).
     */
    void put(String shortCode, String longUrl, java.time.Duration maxTtl);

    /** Cache "this code does not exist", with the much shorter negative TTL. */
    void putAbsent(String shortCode);

    void evict(String shortCode);

    /**
     * Best-effort mutual exclusion for a cache miss on a hot key (SETNX).
     * Returns true if this caller won the right to do the database read.
     * A no-op cache always returns true: with no cache there is no stampede
     * to protect against, every request goes to the DB by design.
     */
    boolean tryAcquireLoadLock(String shortCode);

    void releaseLoadLock(String shortCode);
}
