package com.akshatha.urlshortener.config;

import com.akshatha.urlshortener.repository.UrlMappingRepository;
import com.akshatha.urlshortener.service.ClickCountFlusher;
import com.akshatha.urlshortener.service.ClickCounter;
import com.akshatha.urlshortener.service.IdAllocator;
import com.akshatha.urlshortener.service.NoOpClickCounter;
import com.akshatha.urlshortener.service.NoOpUrlCache;
import com.akshatha.urlshortener.service.RedisBlockIdAllocator;
import com.akshatha.urlshortener.service.RedisClickCounter;
import com.akshatha.urlshortener.service.RedisUrlCache;
import com.akshatha.urlshortener.service.SequenceIdAllocator;
import com.akshatha.urlshortener.service.UrlCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.IntervalTask;

import java.time.Clock;

/**
 * Wires the two interchangeable halves of the system.
 *
 * The M2 implementations (SequenceIdAllocator, NoOpUrlCache) and the M5 ones
 * (RedisBlockIdAllocator, RedisUrlCache) are both live, selected by property.
 * The naive pair is the control group for the load tests, not dead code:
 * re-running the M4 baseline is a config change, not a revert.
 */
@Configuration
@EnableScheduling
public class ServiceConfig {

    private static final Logger log = LoggerFactory.getLogger(ServiceConfig.class);

    /** Injected rather than called statically, so tests can pin time. */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    public IdAllocator idAllocator(ShortenerProperties properties,
                                   UrlMappingRepository repository,
                                   ObjectProvider<StringRedisTemplate> redis) {
        return switch (properties.idAllocator()) {
            case SEQUENCE -> {
                log.info("id allocator: Postgres sequence (one round-trip per shorten)");
                yield new SequenceIdAllocator(repository);
            }
            case REDIS -> {
                long seed = firstIdOfLength(properties.codeLength());
                log.info("id allocator: Redis blocks of {} from seed {}",
                        properties.idBlockSize(), seed);
                yield new RedisBlockIdAllocator(
                        redis.getObject(), properties.idBlockSize(), seed,
                        repository::maxAssignedId);
            }
        };
    }

    @Bean
    public UrlCache urlCache(ShortenerProperties properties,
                             ObjectProvider<StringRedisTemplate> redis) {
        ShortenerProperties.Cache cache = properties.cache();
        if (!cache.enabled()) {
            log.info("url cache: DISABLED — every redirect will hit Postgres (M4 baseline)");
            return new NoOpUrlCache();
        }
        log.info("url cache: Redis, ttl={} negativeTtl={}", cache.ttl(), cache.negativeTtl());
        return new RedisUrlCache(redis.getObject(), cache.ttl(),
                cache.negativeTtl(), cache.stampedeLockTtl());
    }

    @Bean
    public ClickCounter clickCounter(ShortenerProperties properties,
                                     ObjectProvider<StringRedisTemplate> redis) {
        // Without Redis there is nowhere cheap to buffer clicks, and counting
        // them into Postgres on the read path would pollute the very number
        // the baseline exists to measure.
        return properties.cache().enabled()
                ? new RedisClickCounter(redis.getObject())
                : new NoOpClickCounter();
    }

    @Bean
    @ConditionalOnProperty(name = "shortener.cache.enabled", havingValue = "true")
    public ClickCountFlusher clickCountFlusher(StringRedisTemplate redis,
                                               UrlMappingRepository repository) {
        return new ClickCountFlusher(redis, repository);
    }

    /**
     * Scheduled here rather than with @Scheduled(fixedDelayString = ...) so the
     * interval comes from the bound Duration. The annotation would receive the
     * raw property string and parse it itself, which quietly accepts a
     * different set of formats than the configuration binder does.
     */
    @Bean
    public SchedulingConfigurer clickFlushScheduler(ObjectProvider<ClickCountFlusher> flusher,
                                                    ShortenerProperties properties) {
        return registrar -> flusher.ifAvailable(f -> {
            var interval = properties.cache().clickFlushInterval();
            log.info("click count flush scheduled every {}", interval);
            registrar.addFixedDelayTask(new IntervalTask(f::flush, interval));
        });
    }

    /**
     * The smallest id whose Base62 encoding is {@code codeLength} characters,
     * i.e. 62^(codeLength - 1). Matches the START WITH in the V1 migration.
     */
    static long firstIdOfLength(int codeLength) {
        long value = 1;
        for (int i = 1; i < codeLength; i++) {
            value *= 62;
        }
        return value;
    }
}
