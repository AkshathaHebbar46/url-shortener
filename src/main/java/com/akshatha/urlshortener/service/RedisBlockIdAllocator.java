package com.akshatha.urlshortener.service;

import com.akshatha.urlshortener.exception.RedisUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

/**
 * INCRBY a Redis counter by N to claim the id range [hi - N, hi), then serve
 * those ids from memory. One network round-trip per N writes, and two
 * instances can never hand out the same id because INCRBY is atomic.
 *
 * Why INCRBY rather than WATCH/MULTI/EXEC: INCRBY is a single atomic command,
 * so there is no read-modify-write window to guard. WATCH gives optimistic
 * locking, which under contention means retries — strictly more round-trips
 * and more code to do a job one command already does.
 */
public class RedisBlockIdAllocator implements IdAllocator {

    private static final Logger log = LoggerFactory.getLogger(RedisBlockIdAllocator.class);

    public static final String COUNTER_KEY = "shortener:id:counter";

    /**
     * The cursor and the block's upper bound, as ONE immutable value.
     *
     * They have to move together. An earlier version kept them in a separate
     * AtomicLong and volatile long, taking the id with getAndIncrement and
     * then comparing it against the bound. That has a real race: a thread can
     * take a value, be descheduled past a whole refill, and come back to find
     * its now-stale value sits inside the *new* block's range — so it returns
     * an id the refilled cursor will hand out again. It is rare (the thread
     * has to stall across a Redis round-trip) and a 160k-id concurrency test
     * caught it exactly once.
     *
     * Reading both fields in a single volatile read removes the torn pairing:
     * an id is only ever produced by a successful CAS on a (next, end) pair
     * that was consistent when it was read.
     */
    private record Block(long next, long end) {

        boolean hasCapacity() {
            return next < end;
        }

        Block consumed() {
            return new Block(next + 1, end);
        }
    }

    private static final Block EMPTY = new Block(0, 0);

    private final StringRedisTemplate redis;
    private final int blockSize;
    private final long seed;
    private final LongSupplier highWaterMark;

    private final AtomicReference<Block> block = new AtomicReference<>(EMPTY);
    private final Object refillLock = new Object();

    /** Guarded by refillLock. Negative until the high-water mark has been read. */
    private long floor = -1;
    private boolean seedChecked = false;

    /**
     * @param seed          first id ever issued, so that the first code is
     *                      already codeLength characters wide (62^(codeLength - 1)).
     * @param highWaterMark highest id already in the database. Consulted once,
     *                      when this JVM first seeds the Redis counter.
     */
    public RedisBlockIdAllocator(StringRedisTemplate redis, int blockSize, long seed,
                                 LongSupplier highWaterMark) {
        this.redis = redis;
        this.blockSize = blockSize;
        this.seed = seed;
        this.highWaterMark = highWaterMark;
    }

    @Override
    public long nextId() {
        while (true) {
            Block current = block.get();
            if (current.hasCapacity()) {
                // Losing the CAS means another thread took this id; loop and
                // read a fresh pair rather than assuming anything about it.
                if (block.compareAndSet(current, current.consumed())) {
                    return current.next();
                }
                continue;
            }
            refill(current);
        }
    }

    /**
     * Only one thread goes to Redis; the rest block here, then re-check and
     * find the block already refilled.
     *
     * The whole new (next, end) pair is published in one atomic write, so
     * there is no window in which a stale cursor can be read against a fresh
     * bound.
     */
    private void refill(Block observed) {
        synchronized (refillLock) {
            // Someone refilled while this thread waited for the lock.
            if (block.get() != observed) {
                return;
            }

            long hi = claimBlock();
            block.set(new Block(hi - blockSize, hi));
            log.debug("claimed id block [{}, {})", hi - blockSize, hi);
        }
    }

    /** Must be called under refillLock. */
    private long claimBlock() {
        try {
            long lowest = floor();

            if (!seedChecked) {
                // SETNX, so the first instance to start seeds the counter and
                // everyone else leaves it alone. Without this the counter would
                // begin at 0 and the first codes would be one character long.
                redis.opsForValue().setIfAbsent(COUNTER_KEY, Long.toString(lowest));
                seedChecked = true;
            }

            Long hi = redis.opsForValue().increment(COUNTER_KEY, blockSize);
            if (hi == null) {
                throw new RedisUnavailableException("Redis returned no value for INCRBY", null);
            }

            if (hi < lowest + blockSize) {
                // The counter was flushed, reset, or is simply behind the
                // database. Reissuing ids that are already persisted would
                // collide on short_code for every write until the counter
                // caught up, so jump the counter clear of them.
                log.warn("id counter was at {}, below the floor of {} — re-seeding", hi, lowest);
                redis.opsForValue().set(COUNTER_KEY, Long.toString(lowest + blockSize));
                return lowest + blockSize;
            }
            return hi;
        } catch (DataAccessException e) {
            throw new RedisUnavailableException(
                    "cannot allocate an id block: Redis is unreachable", e);
        }
    }

    /**
     * The lowest id it is safe to issue, read once per JVM.
     *
     * Redis is configured without persistence, deliberately — it is a cache.
     * Postgres is durable. So a Redis restart drops the counter back to zero
     * while the rows it already issued ids for are still there, and seeding
     * from {@code seed} alone would replay ids that exist, failing every write
     * on the unique constraint until the counter climbed past them.
     *
     * Taking the database's high-water mark closes that gap. Called under
     * refillLock, and memoised, so it costs one indexed query per JVM.
     */
    private long floor() {
        if (floor < 0) {
            try {
                floor = Math.max(seed, highWaterMark.getAsLong() + 1);
            } catch (RuntimeException e) {
                // The database is a hard dependency of the write path anyway,
                // so a failure here will surface at the INSERT. Fall back to
                // the static seed rather than making id allocation the thing
                // that reports it.
                log.warn("could not read the id high-water mark, falling back to the seed", e);
                floor = seed;
            }
        }
        return floor;
    }

    /** Exposed for tests: how many ids are left in the block held right now. */
    long remainingInBlock() {
        Block current = block.get();
        return Math.max(0, current.end() - current.next());
    }
}
