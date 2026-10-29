package com.akshatha.urlshortener.config;

import io.github.bucket4j.distributed.ExpirationAfterWriteStrategy;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import io.github.bucket4j.redis.lettuce.Bucket4jLettuce;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.servlet.HandlerExceptionResolver;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.function.Supplier;

/**
 * Bucket4j over Lettuce, so rate limit state is shared by every instance.
 *
 * Bucket4j needs a byte[]-valued Lettuce connection of its own; it cannot
 * share Spring Data's RedisTemplate, whose serializers it does not control.
 * This client is separate from the one backing RedisUrlCache on purpose.
 */
@Configuration
@ConditionalOnProperty(name = "shortener.rate-limit.enabled", havingValue = "true",
        matchIfMissing = true)
public class RateLimitConfig {

    private static final Logger log = LoggerFactory.getLogger(RateLimitConfig.class);

    /**
     * Buckets idle for longer than their full refill window carry no useful
     * state, so Redis is allowed to reclaim them. Without an expiry strategy
     * the keyspace grows by one key per client IP, forever.
     */
    private static final Duration BUCKET_IDLE_TTL = Duration.ofMinutes(5);

    /**
     * Deliberately NOT spring.data.redis.timeout (200ms).
     *
     * That value is a command timeout tuned for the cache's hot path, and
     * Lettuce applies the same budget to connection initialisation — the
     * handshake does not reliably complete inside it, so the limiter would
     * fail on the very first request after every reconnect. One second covers
     * the handshake; the commands themselves are a single CAS round-trip and
     * nowhere near it.
     */
    private static final Duration RATE_LIMIT_TIMEOUT = Duration.ofSeconds(1);

    /** RedisClient.create does not open a connection, so this stays cheap. */
    @Bean(destroyMethod = "shutdown")
    public RedisClient rateLimitRedisClient(RedisProperties properties) {
        RedisURI.Builder uri = RedisURI.builder()
                .withHost(properties.getHost())
                .withPort(properties.getPort())
                .withDatabase(properties.getDatabase())
                .withTimeout(RATE_LIMIT_TIMEOUT);

        if (properties.getUsername() != null && properties.getPassword() != null) {
            uri.withAuthentication(properties.getUsername(), properties.getPassword().toCharArray());
        } else if (properties.getPassword() != null) {
            uri.withPassword(properties.getPassword().toCharArray());
        }
        return RedisClient.create(uri.build());
    }

    /**
     * Built on first use, not at startup.
     *
     * Bucket4jLettuce.casBasedBuilder() opens the connection eagerly, so
     * creating the ProxyManager as an ordinary @Bean would make a Redis outage
     * stop the application from starting at all. That directly contradicts the
     * rest of the design: the cache degrades to a miss when Redis is down and
     * reads keep being served, so the rate limiter must not be the thing that
     * takes the service down instead.
     *
     * Deferring it means a startup with Redis unreachable succeeds, and the
     * first request pays the connection attempt inside RateLimitFilter's
     * try/catch, which then applies the configured fail-open or fail-closed
     * behaviour. The reference is cached once a connection succeeds, so the
     * cost is paid once rather than per request.
     */
    static Supplier<ProxyManager<String>> lazyProxyManager(RedisClient client) {
        return new Supplier<>() {
            private volatile ProxyManager<String> delegate;

            @Override
            public ProxyManager<String> get() {
                ProxyManager<String> current = delegate;
                if (current == null) {
                    synchronized (this) {
                        current = delegate;
                        if (current == null) {
                            log.info("connecting the rate limiter to Redis");
                            current = Bucket4jLettuce.casBasedBuilder(client)
                                    .expirationAfterWrite(
                                            ExpirationAfterWriteStrategy
                                                    .basedOnTimeForRefillingBucketUpToMax(
                                                            BUCKET_IDLE_TTL))
                                    .build()
                                    .withMapper(key -> key.getBytes(StandardCharsets.UTF_8));
                            delegate = current;
                        }
                    }
                }
                return current;
            }
        };
    }

    /**
     * Registered explicitly with a late order so it runs AFTER Spring's
     * ForwardedHeaderFilter. That ordering is what makes getRemoteAddr() the
     * real client address behind Nginx rather than the proxy's — see the note
     * on RateLimitFilter.clientIp.
     */
    @Bean
    public FilterRegistrationBean<RateLimitFilter> rateLimitFilterRegistration(
            RedisClient client,
            ShortenerProperties properties,
            @Qualifier("handlerExceptionResolver") HandlerExceptionResolver exceptionResolver) {

        FilterRegistrationBean<RateLimitFilter> registration = new FilterRegistrationBean<>(
                new RateLimitFilter(lazyProxyManager(client), properties.rateLimit(),
                        exceptionResolver));
        registration.setOrder(Ordered.LOWEST_PRECEDENCE - 100);
        return registration;
    }
}
