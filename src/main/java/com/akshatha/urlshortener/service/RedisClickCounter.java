package com.akshatha.urlshortener.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Buffers click counts in a Redis hash with HINCRBY, one command, no row lock.
 * ClickCountFlusher drains the hash into Postgres on a schedule.
 *
 * The cost of this design is that a Redis loss drops at most one flush
 * interval of clicks. For analytics that is an acceptable trade against adding
 * a write round-trip and contention on the hottest rows in the table to every
 * redirect.
 */
public class RedisClickCounter implements ClickCounter {

    private static final Logger log = LoggerFactory.getLogger(RedisClickCounter.class);

    static final String BUFFER_KEY = "shortener:clicks";

    private final StringRedisTemplate redis;

    public RedisClickCounter(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public void record(String shortCode) {
        try {
            redis.opsForHash().increment(BUFFER_KEY, shortCode, 1L);
        } catch (DataAccessException e) {
            // Analytics must never break a redirect.
            log.debug("click buffering failed for {}", shortCode, e);
        }
    }
}
