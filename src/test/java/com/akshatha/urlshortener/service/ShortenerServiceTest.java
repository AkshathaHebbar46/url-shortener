package com.akshatha.urlshortener.service;

import com.akshatha.urlshortener.config.ShortenerProperties;
import com.akshatha.urlshortener.domain.UrlMapping;
import com.akshatha.urlshortener.exception.AliasAlreadyExistsException;
import com.akshatha.urlshortener.exception.ShortCodeExpiredException;
import com.akshatha.urlshortener.exception.ShortCodeNotFoundException;
import com.akshatha.urlshortener.repository.UrlMappingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ShortenerServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-28T12:00:00Z");
    private static final long SEED = 56_800_235_584L;

    private UrlMappingRepository repository;
    private IdAllocator idAllocator;
    private UrlCache cache;
    private ClickCounter clickCounter;
    private ShortenerService service;

    @BeforeEach
    void setUp() {
        repository = mock(UrlMappingRepository.class);
        idAllocator = mock(IdAllocator.class);
        cache = mock(UrlCache.class);
        clickCounter = mock(ClickCounter.class);

        ShortenerProperties properties = new ShortenerProperties(
                "https://sho.rt", 7, 1000,
                ShortenerProperties.Allocator.SEQUENCE,
                new ShortenerProperties.Cache(true, Duration.ofHours(24),
                        Duration.ofSeconds(60), Duration.ofSeconds(2), Duration.ofSeconds(30)),
                new ShortenerProperties.RateLimit(true, 10, 100, true));

        service = new ShortenerService(repository, idAllocator, cache, clickCounter,
                properties, Clock.fixed(NOW, ZoneOffset.UTC));

        when(cache.tryAcquireLoadLock(anyString())).thenReturn(true);
    }

    // ---- read path ----

    @Test
    void aCacheHitNeverTouchesTheDatabase() {
        when(cache.get("abc1234")).thenReturn(new CacheLookup.Hit("https://example.com"));

        assertThat(service.resolve("abc1234")).isEqualTo("https://example.com");

        verifyNoInteractions(repository);
        verify(clickCounter).record("abc1234");
    }

    /**
     * The negative cache earning its keep: a flood of junk codes is answered
     * without a database round-trip each.
     */
    @Test
    void aKnownAbsentEntryShortCircuitsWithoutHittingTheDatabase() {
        when(cache.get("nosuch1")).thenReturn(CacheLookup.KNOWN_ABSENT);

        assertThatThrownBy(() -> service.resolve("nosuch1"))
                .isInstanceOf(ShortCodeNotFoundException.class);

        verifyNoInteractions(repository);
    }

    @Test
    void aMissReadsTheDatabaseAndPopulatesTheCache() {
        when(cache.get("abc1234")).thenReturn(CacheLookup.MISS);
        when(repository.findByShortCode("abc1234")).thenReturn(Optional.of(
                new UrlMapping(SEED, "abc1234", "https://example.com", NOW, null)));

        assertThat(service.resolve("abc1234")).isEqualTo("https://example.com");

        verify(cache).put("abc1234", "https://example.com");
        verify(cache).releaseLoadLock("abc1234");
        verify(clickCounter).record("abc1234");
    }

    @Test
    void anUnknownCodeIsCachedNegativelyAndThen404s() {
        when(cache.get("nosuch1")).thenReturn(CacheLookup.MISS);
        when(repository.findByShortCode("nosuch1")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.resolve("nosuch1"))
                .isInstanceOf(ShortCodeNotFoundException.class);

        verify(cache).putAbsent("nosuch1");
        verify(cache).releaseLoadLock("nosuch1");
    }

    @Test
    void anExpiredMappingIsGoneAndIsNotServed() {
        when(cache.get("old1234")).thenReturn(CacheLookup.MISS);
        when(repository.findByShortCode("old1234")).thenReturn(Optional.of(
                new UrlMapping(SEED, "old1234", "https://example.com",
                        NOW.minus(Duration.ofDays(2)), NOW.minus(Duration.ofHours(1)))));

        assertThatThrownBy(() -> service.resolve("old1234"))
                .isInstanceOf(ShortCodeExpiredException.class);

        verify(cache).putAbsent("old1234");
        verify(cache, never()).put(anyString(), anyString());
        verify(clickCounter, never()).record(anyString());
    }

    /**
     * A mapping that expires in 10 minutes must not be cached for 24 hours, or
     * the code would keep resolving long after it died.
     */
    @Test
    void aMappingWithAnExpiryIsCachedNoLongerThanItsRemainingLife() {
        Instant expiresAt = NOW.plus(Duration.ofMinutes(10));
        when(cache.get("soon123")).thenReturn(CacheLookup.MISS);
        when(repository.findByShortCode("soon123")).thenReturn(Optional.of(
                new UrlMapping(SEED, "soon123", "https://example.com", NOW, expiresAt)));

        service.resolve("soon123");

        verify(cache).put("soon123", "https://example.com", Duration.ofMinutes(10));
        verify(cache, never()).put(anyString(), anyString());
    }

    /**
     * Losing the stampede lock must not fail the request. The loser re-checks
     * the cache, and falls through to the database if the winner has not
     * populated it yet — the lock is an optimisation, not a barrier.
     */
    @Test
    void aLoserOfTheStampedeLockServesFromTheCacheOnceTheWinnerPopulatesIt() {
        when(cache.tryAcquireLoadLock("hot1234")).thenReturn(false);
        when(cache.get("hot1234"))
                .thenReturn(CacheLookup.MISS)
                .thenReturn(new CacheLookup.Hit("https://example.com"));

        assertThat(service.resolve("hot1234")).isEqualTo("https://example.com");

        verifyNoInteractions(repository);
        verify(cache, never()).releaseLoadLock(anyString());
    }

    @Test
    void aLoserOfTheStampedeLockStillFallsThroughToTheDatabase() {
        when(cache.tryAcquireLoadLock("hot1234")).thenReturn(false);
        when(cache.get("hot1234")).thenReturn(CacheLookup.MISS);
        when(repository.findByShortCode("hot1234")).thenReturn(Optional.of(
                new UrlMapping(SEED, "hot1234", "https://example.com", NOW, null)));

        assertThat(service.resolve("hot1234")).isEqualTo("https://example.com");

        // It did not take the lock, so it must not release someone else's.
        verify(cache, never()).releaseLoadLock(anyString());
    }

    // ---- write path ----

    @Test
    void shortenEncodesTheAllocatedIdAsTheShortCode() {
        when(idAllocator.nextId()).thenReturn(SEED);
        when(repository.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));

        UrlMapping mapping = service.shorten("https://example.com", null, null);

        assertThat(mapping.getId()).isEqualTo(SEED);
        assertThat(mapping.getShortCode()).isEqualTo("1000000").hasSize(7);
        assertThat(mapping.getCreatedAt()).isEqualTo(NOW);
        // Clears any stale negative entry, or the new code would 404 for a minute.
        verify(cache).evict("1000000");
    }

    @Test
    void shortenRetriesOnAUniqueConstraintCollision() {
        when(idAllocator.nextId()).thenReturn(SEED, SEED + 1);
        when(repository.saveAndFlush(any()))
                .thenThrow(new DataIntegrityViolationException("duplicate key"))
                .thenAnswer(i -> i.getArgument(0));

        UrlMapping mapping = service.shorten("https://example.com", null, null);

        assertThat(mapping.getId()).isEqualTo(SEED + 1);
        verify(repository, times(2)).saveAndFlush(any());
    }

    @Test
    void shortenGivesUpAfterABoundedNumberOfCollisions() {
        when(idAllocator.nextId()).thenReturn(SEED);
        when(repository.saveAndFlush(any()))
                .thenThrow(new DataIntegrityViolationException("duplicate key"));

        assertThatThrownBy(() -> service.shorten("https://example.com", null, null))
                .isInstanceOf(IllegalStateException.class);

        verify(repository, times(3)).saveAndFlush(any());
    }

    /** A taken alias is a 409, not a silent substitution of a different code. */
    @Test
    void aTakenCustomAliasIsAConflict() {
        when(repository.existsByShortCode("promo")).thenReturn(true);

        assertThatThrownBy(() -> service.shorten("https://example.com", "promo", null))
                .isInstanceOf(AliasAlreadyExistsException.class);

        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void anAliasLostInARaceIsAlsoAConflictRatherThanARetry() {
        when(repository.existsByShortCode("promo")).thenReturn(false);
        when(idAllocator.nextId()).thenReturn(SEED);
        when(repository.saveAndFlush(any()))
                .thenThrow(new DataIntegrityViolationException("duplicate key"));

        assertThatThrownBy(() -> service.shorten("https://example.com", "promo", null))
                .isInstanceOf(AliasAlreadyExistsException.class);

        verify(repository, times(1)).saveAndFlush(any());
    }

    /**
     * The allocator refuses to issue ids when Redis is down rather than
     * colliding with it; that must surface as a retryable 503, not a 500.
     */
    @Test
    void aRedisOutageOnTheWritePathPropagatesAsRedisUnavailable() {
        when(idAllocator.nextId())
                .thenThrow(new com.akshatha.urlshortener.exception.RedisUnavailableException(
                        "cannot allocate an id block: Redis is unreachable", null));

        assertThatThrownBy(() -> service.shorten("https://example.com", null, null))
                .isInstanceOf(com.akshatha.urlshortener.exception.RedisUnavailableException.class);

        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void shortUrlForJoinsTheBaseUrlWithExactlyOneSlash() {
        assertThat(service.shortUrlFor("abc1234")).isEqualTo("https://sho.rt/abc1234");
    }
}
