package com.akshatha.urlshortener.service;

import com.akshatha.urlshortener.repository.UrlMappingRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Drains the Redis click buffer into Postgres as one bulk UPDATE per interval,
 * rather than one UPDATE per click.
 *
 * The drain is RENAME-then-read, not read-then-delete: RENAME is atomic, so
 * clicks arriving during the flush land in a fresh buffer under the original
 * key instead of being counted and then deleted unread.
 *
 * Scheduled programmatically from ServiceConfig so the interval comes from the
 * bound Duration rather than a string parsed at annotation time.
 */
public class ClickCountFlusher {

    private static final Logger log = LoggerFactory.getLogger(ClickCountFlusher.class);

    private final StringRedisTemplate redis;
    private final UrlMappingRepository repository;

    public ClickCountFlusher(StringRedisTemplate redis, UrlMappingRepository repository) {
        this.redis = redis;
        this.repository = repository;
    }

    @Transactional
    public void flush() {
        String snapshotKey = RedisClickCounter.BUFFER_KEY + ":flush:" + UUID.randomUUID();

        Map<Object, Object> counts;
        try {
            if (!Boolean.TRUE.equals(redis.hasKey(RedisClickCounter.BUFFER_KEY))) {
                return;
            }
            redis.rename(RedisClickCounter.BUFFER_KEY, snapshotKey);
            counts = redis.opsForHash().entries(snapshotKey);
        } catch (DataAccessException e) {
            log.warn("could not drain the click buffer, will retry next interval", e);
            return;
        }

        if (counts.isEmpty()) {
            redis.delete(snapshotKey);
            return;
        }

        List<String> codes = new ArrayList<>(counts.size());
        List<Long> deltas = new ArrayList<>(counts.size());
        counts.forEach((code, delta) -> {
            try {
                deltas.add(Long.parseLong(delta.toString()));
                codes.add(code.toString());
            } catch (NumberFormatException e) {
                log.warn("skipping unparseable click count for {}: {}", code, delta);
            }
        });

        try {
            int updated = repository.incrementClickCounts(
                    codes.toArray(String[]::new), deltas.toArray(Long[]::new));
            log.debug("flushed {} click deltas, {} rows updated", codes.size(), updated);
            redis.delete(snapshotKey);
        } catch (RuntimeException e) {
            // The snapshot key is left in place on purpose: the counts are
            // still in Redis and recoverable by hand. Deleting here would turn
            // a failed flush into silent data loss.
            log.error("click flush failed, counts preserved under {}", snapshotKey, e);
            throw e;
        }
    }
}
