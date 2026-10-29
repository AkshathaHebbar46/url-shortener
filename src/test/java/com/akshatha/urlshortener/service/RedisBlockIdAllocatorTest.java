package com.akshatha.urlshortener.service;

import com.akshatha.urlshortener.exception.RedisUnavailableException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.RepeatedTest;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A concurrency bug here is silent in production and corrupts data, so these
 * tests run the real race rather than a single-threaded approximation.
 *
 * Note that a passing concurrency test does not prove the absence of a race.
 * The @RepeatedTest below exists for that reason; run it with a much higher
 * repetition count before trusting a change to the refill path.
 */
class RedisBlockIdAllocatorTest {

    private static final long SEED = 56_800_235_584L;

    /** Most tests start from an empty url_mapping table. */
    private static final java.util.function.LongSupplier EMPTY_TABLE = () -> 0L;

    private StringRedisTemplate redis;
    private ValueOperations<String, String> valueOps;

    /** Stands in for the Redis counter; addAndGet gives the same atomicity INCRBY does. */
    private AtomicLong counter;

    /** How many times Redis was actually asked for a block. */
    private AtomicInteger redisCalls;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        counter = new AtomicLong(SEED);
        redisCalls = new AtomicInteger();

        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.setIfAbsent(anyString(), anyString())).thenReturn(true);
        when(valueOps.increment(anyString(), anyLong())).thenAnswer(invocation -> {
            redisCalls.incrementAndGet();
            return counter.addAndGet(invocation.getArgument(1, Long.class));
        });
    }

    @Test
    void handsOutDistinctIdsUnderConcurrency() throws Exception {
        int threads = 16;
        int idsPerThread = 10_000;
        int blockSize = 1_000;
        RedisBlockIdAllocator allocator = new RedisBlockIdAllocator(redis, blockSize, SEED, EMPTY_TABLE);

        Set<Long> ids = pullConcurrently(allocator, threads, idsPerThread);

        // The whole guarantee of the class: no id is ever issued twice.
        assertThat(ids).hasSize(threads * idsPerThread);
        assertThat(ids).allMatch(id -> id >= SEED);
    }

    /**
     * A tiny block size makes a short test cross many refill boundaries, which
     * is where the races live.
     */
    @RepeatedTest(25)
    void staysUniqueAcrossManyBlockBoundaries() throws Exception {
        int threads = 8;
        int idsPerThread = 500;
        RedisBlockIdAllocator allocator = new RedisBlockIdAllocator(redis, 10, SEED, EMPTY_TABLE);

        Set<Long> ids = pullConcurrently(allocator, threads, idsPerThread);

        assertThat(ids).hasSize(threads * idsPerThread);
    }

    /**
     * The entire point of the class is amortising the round-trip. Assert it
     * rather than assuming it: a refill-every-call regression would still pass
     * every uniqueness test above.
     */
    @Test
    void callsRedisOncePerBlockNotOncePerId() throws Exception {
        int threads = 16;
        int idsPerThread = 10_000;
        int blockSize = 1_000;
        int totalIds = threads * idsPerThread;
        RedisBlockIdAllocator allocator = new RedisBlockIdAllocator(redis, blockSize, SEED, EMPTY_TABLE);

        pullConcurrently(allocator, threads, idsPerThread);

        int minimumPossible = totalIds / blockSize;
        assertThat(redisCalls.get())
                .as("%d ids at a block size of %d", totalIds, blockSize)
                .isGreaterThanOrEqualTo(minimumPossible)
                // Slack for the handful of ids discarded in the refill window.
                .isLessThan(minimumPossible * 2);
    }

    @Test
    void isMonotonicWithinABlockOnASingleThread() {
        RedisBlockIdAllocator allocator = new RedisBlockIdAllocator(redis, 100, SEED, EMPTY_TABLE);

        long previous = allocator.nextId();
        assertThat(previous).isEqualTo(SEED);
        for (int i = 1; i < 500; i++) {
            long next = allocator.nextId();
            assertThat(next).isGreaterThan(previous);
            previous = next;
        }
        // 500 ids out of 100-id blocks is exactly five round-trips.
        assertThat(redisCalls.get()).isEqualTo(5);
    }

    /**
     * The horizontal-scale guarantee, with two allocators sharing one counter.
     * The shared AtomicLong plays the part Redis plays in production: a single
     * atomic INCRBY that neither instance can get ahead of.
     */
    @Test
    void twoInstancesSharingOneCounterNeverOverlap() throws Exception {
        RedisBlockIdAllocator first = new RedisBlockIdAllocator(redis, 50, SEED, EMPTY_TABLE);
        RedisBlockIdAllocator second = new RedisBlockIdAllocator(redis, 50, SEED, EMPTY_TABLE);

        Set<Long> ids = ConcurrentHashMap.newKeySet();
        CountDownLatch start = new CountDownLatch(1);
        List<Thread> threads = new ArrayList<>();
        for (RedisBlockIdAllocator allocator : List.of(first, second)) {
            for (int t = 0; t < 4; t++) {
                threads.add(Thread.ofPlatform().unstarted(() -> {
                    awaitQuietly(start);
                    for (int i = 0; i < 2_000; i++) {
                        ids.add(allocator.nextId());
                    }
                }));
            }
        }
        threads.forEach(Thread::start);
        start.countDown();
        for (Thread thread : threads) {
            thread.join(TimeUnit.SECONDS.toMillis(30));
        }

        assertThat(ids).hasSize(8 * 2_000);
    }

    /**
     * Writes fail loudly when Redis is unreachable. The allocator deliberately
     * does NOT fall back to the Postgres sequence: that counter has no idea
     * which ids Redis already issued, so the two would overlap. See
     * docs/decisions.md.
     */
    @Test
    void failsTheWriteWhenRedisIsUnreachable() {
        when(valueOps.increment(anyString(), anyLong()))
                .thenThrow(new RedisConnectionFailureException("connection refused"));
        RedisBlockIdAllocator allocator = new RedisBlockIdAllocator(redis, 100, SEED, EMPTY_TABLE);

        assertThatThrownBy(allocator::nextId)
                .isInstanceOf(RedisUnavailableException.class)
                .hasMessageContaining("Redis is unreachable");
    }

    /** A flushed Redis must not drop the counter back into 1-character codes. */
    @Test
    void reSeedsIfTheCounterFallsBelowTheSeed() {
        counter.set(0);
        RedisBlockIdAllocator allocator = new RedisBlockIdAllocator(redis, 100, SEED, EMPTY_TABLE);

        assertThat(allocator.nextId()).isGreaterThanOrEqualTo(SEED);
    }

    /**
     * Hammers the refill boundary specifically.
     *
     * A block size of 4 against 12 threads means a refill roughly every third
     * id, which is the window where the duplicate-id race lives: a thread has
     * to read the cursor, stall across a whole refill, and come back. The
     * earlier two-field implementation (AtomicLong cursor + volatile bound)
     * failed this; it also survived 160k ids many times before failing once,
     * which is why this runs repeatedly rather than once.
     */
    @RepeatedTest(50)
    void neverIssuesADuplicateWhenRefillsAreConstant() throws Exception {
        int threads = 12;
        int idsPerThread = 250;
        RedisBlockIdAllocator allocator = new RedisBlockIdAllocator(redis, 4, SEED, EMPTY_TABLE);

        Set<Long> ids = pullConcurrently(allocator, threads, idsPerThread);

        assertThat(ids).hasSize(threads * idsPerThread);
    }

    /**
     * The Redis-is-ephemeral, Postgres-is-durable mismatch.
     *
     * Redis runs without persistence on purpose — it is a cache. So a restart
     * drops the id counter to nothing while the rows it already issued ids for
     * are still in Postgres. Seeding from the static 62^6 alone would replay
     * those ids and collide on short_code for every write until the counter
     * climbed past them.
     */
    @Test
    void doesNotReissueIdsThatArePersistedWhenTheCounterIsLost() {
        long lastPersisted = SEED + 7;     // rows 1000000..1000007 survived
        counter.set(0);                    // but Redis was flushed
        RedisBlockIdAllocator allocator =
                new RedisBlockIdAllocator(redis, 100, SEED, () -> lastPersisted);

        long first = allocator.nextId();

        assertThat(first)
                .as("first id after a Redis wipe must clear the persisted rows")
                .isGreaterThan(lastPersisted);
    }

    /** An empty table must still start at the seed, not at 1. */
    @Test
    void startsAtTheSeedWhenTheTableIsEmpty() {
        counter.set(0);
        RedisBlockIdAllocator allocator = new RedisBlockIdAllocator(redis, 100, SEED, EMPTY_TABLE);

        assertThat(allocator.nextId()).isGreaterThanOrEqualTo(SEED);
    }

    /** A database that cannot be read must not take id allocation down with it. */
    @Test
    void fallsBackToTheSeedIfTheHighWaterMarkCannotBeRead() {
        RedisBlockIdAllocator allocator = new RedisBlockIdAllocator(redis, 100, SEED, () -> {
            throw new IllegalStateException("no connection");
        });

        assertThat(allocator.nextId()).isGreaterThanOrEqualTo(SEED);
    }

    /** Starts every thread at the same instant, so the race actually fires. */
    private Set<Long> pullConcurrently(RedisBlockIdAllocator allocator, int threads, int idsPerThread)
            throws InterruptedException {

        Set<Long> ids = ConcurrentHashMap.newKeySet();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);

        for (int t = 0; t < threads; t++) {
            Thread.ofPlatform().start(() -> {
                try {
                    awaitQuietly(start);
                    // Collect locally first: contending on the shared set would
                    // serialise the threads and hide the race being tested.
                    long[] pulled = new long[idsPerThread];
                    for (int i = 0; i < idsPerThread; i++) {
                        pulled[i] = allocator.nextId();
                    }
                    for (long id : pulled) {
                        ids.add(id);
                    }
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        assertThat(done.await(60, TimeUnit.SECONDS)).as("threads finished").isTrue();
        return ids;
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
