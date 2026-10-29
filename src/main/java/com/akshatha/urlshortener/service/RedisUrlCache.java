package com.akshatha.urlshortener.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;

/**
 * Cache-aside storage for code -> URL, in plain strings.
 *
 * Keys are namespaced ("url:{code}", "lock:url:{code}") because this keyspace
 * also holds the id counter and the click buffer; an un-prefixed keyspace
 * becomes undebuggable the moment it holds more than one kind of thing.
 *
 * Nothing here throws. A cache that propagates failures turns a degraded
 * dependency into a full outage, so every Redis error is logged and reported
 * as a miss, leaving Postgres to serve the request.
 */
public class RedisUrlCache implements UrlCache {

    private static final Logger log = LoggerFactory.getLogger(RedisUrlCache.class);

    private static final String KEY_PREFIX = "url:";
    private static final String LOCK_PREFIX = "lock:url:";

    /**
     * Negative-cache sentinel. Safe to distinguish by value: @SafeUrl only
     * admits absolute http(s) URLs, so no real cached value can equal this.
     */
    private static final String ABSENT = "\u0000absent";

    private final StringRedisTemplate redis;
    private final Duration ttl;
    private final Duration negativeTtl;
    private final Duration lockTtl;

    public RedisUrlCache(StringRedisTemplate redis,
                         Duration ttl,
                         Duration negativeTtl,
                         Duration lockTtl) {
        this.redis = redis;
        this.ttl = ttl;
        this.negativeTtl = negativeTtl;
        this.lockTtl = lockTtl;
    }

    @Override
    public CacheLookup get(String shortCode) {
        try {
            String value = redis.opsForValue().get(KEY_PREFIX + shortCode);
            if (value == null) {
                return CacheLookup.MISS;
            }
            return ABSENT.equals(value) ? CacheLookup.KNOWN_ABSENT : new CacheLookup.Hit(value);
        } catch (DataAccessException e) {
            log.warn("cache get failed for {}, falling through to the database", shortCode, e);
            return CacheLookup.MISS;
        }
    }

    @Override
    public void put(String shortCode, String longUrl) {
        write(shortCode, longUrl, ttl);
    }

    @Override
    public void put(String shortCode, String longUrl, Duration maxTtl) {
        if (maxTtl == null || maxTtl.isNegative() || maxTtl.isZero()) {
            return;
        }
        write(shortCode, longUrl, maxTtl.compareTo(ttl) < 0 ? maxTtl : ttl);
    }

    @Override
    public void putAbsent(String shortCode) {
        // Deliberately much shorter than the positive TTL: a negative entry is
        // a statement about something that does not exist *yet*, and a code
        // created a second from now must not stay invisible for a day.
        write(shortCode, ABSENT, negativeTtl);
    }

    @Override
    public void evict(String shortCode) {
        try {
            redis.delete(KEY_PREFIX + shortCode);
        } catch (DataAccessException e) {
            log.warn("cache evict failed for {}", shortCode, e);
        }
    }

    /**
     * SETNX with a TTL. The TTL is what stops a caller that dies mid-load from
     * wedging the key for everyone; the lock is best-effort either way, since
     * ShortenerService falls through to the database rather than waiting.
     */
    @Override
    public boolean tryAcquireLoadLock(String shortCode) {
        try {
            return Boolean.TRUE.equals(
                    redis.opsForValue().setIfAbsent(LOCK_PREFIX + shortCode, "1", lockTtl));
        } catch (DataAccessException e) {
            log.warn("stampede lock unavailable for {}, proceeding without it", shortCode, e);
            return true;
        }
    }

    @Override
    public void releaseLoadLock(String shortCode) {
        try {
            redis.delete(LOCK_PREFIX + shortCode);
        } catch (DataAccessException e) {
            log.warn("stampede lock release failed for {}", shortCode, e);
        }
    }

    private void write(String shortCode, String value, Duration expiry) {
        try {
            redis.opsForValue().set(KEY_PREFIX + shortCode, value, expiry);
        } catch (DataAccessException e) {
            log.warn("cache put failed for {}", shortCode, e);
        }
    }
}
