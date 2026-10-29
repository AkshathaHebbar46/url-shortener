package com.akshatha.urlshortener.config;

import com.akshatha.urlshortener.exception.RateLimitExceededException;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Distributed rate limiting. A public shortener without this gets abused
 * immediately.
 *
 * The buckets live in Redis through Bucket4j's Lettuce proxy manager, so the
 * limit is shared across instances. Per-instance buckets would silently
 * multiply the real limit by the number of pods.
 */
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    private static final String SHORTEN_PATH = "/api/v1/shorten";

    /** Resolved on first use; see RateLimitConfig.lazyProxyManager. */
    private final Supplier<ProxyManager<String>> buckets;
    private final ShortenerProperties.RateLimit config;
    private final HandlerExceptionResolver exceptionResolver;

    private final Supplier<BucketConfiguration> shortenConfig;
    private final Supplier<BucketConfiguration> redirectConfig;

    public RateLimitFilter(Supplier<ProxyManager<String>> buckets,
                           ShortenerProperties.RateLimit config,
                           HandlerExceptionResolver exceptionResolver) {
        this.buckets = buckets;
        this.config = config;
        this.exceptionResolver = exceptionResolver;
        this.shortenConfig = perMinute(config.shortenPerMinute());
        this.redirectConfig = perMinute(config.redirectPerMinute());
    }

    /**
     * Operational and documentation endpoints are exempt. Rate limiting
     * /actuator/health in particular would let ordinary traffic starve the
     * load balancer's probe and take the instance out of rotation.
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.startsWith("/actuator")
                || path.startsWith("/swagger-ui")
                || path.startsWith("/v3/api-docs")
                || path.equals("/favicon.ico");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        boolean isShorten = SHORTEN_PATH.equals(request.getRequestURI());
        String key = (isShorten ? "rl:shorten:" : "rl:redirect:") + clientIp(request);
        Supplier<BucketConfiguration> bucketConfig = isShorten ? shortenConfig : redirectConfig;

        ConsumptionProbe probe;
        try {
            // Resolving the proxy manager may itself open the Redis
            // connection, so it sits inside the same try as the consume.
            probe = buckets.get().getProxy(key, bucketConfig).tryConsumeAndReturnRemaining(1);
        } catch (RuntimeException e) {
            // Fail open by default: a Redis outage should degrade the limiter,
            // not the service. Fail-closed turns a cache outage into a total
            // outage. Configurable, because the right answer depends on how
            // much abuse the endpoint attracts — see docs/decisions.md.
            if (config.failOpen()) {
                log.warn("rate limiter unavailable, allowing request (fail-open)", e);
                filterChain.doFilter(request, response);
            } else {
                log.warn("rate limiter unavailable, rejecting request (fail-closed)", e);
                reject(request, response, 5);
            }
            return;
        }

        if (!probe.isConsumed()) {
            long retryAfter = Math.max(1,
                    TimeUnit.NANOSECONDS.toSeconds(probe.getNanosToWaitForRefill()));
            reject(request, response, retryAfter);
            return;
        }

        response.setHeader("X-RateLimit-Remaining", Long.toString(probe.getRemainingTokens()));
        filterChain.doFilter(request, response);
    }

    /**
     * Routes the 429 through GlobalExceptionHandler, so the body shape matches
     * every other error the API produces. A filter sits outside the
     * DispatcherServlet, so a thrown exception would otherwise never reach the
     * @RestControllerAdvice.
     */
    private void reject(HttpServletRequest request, HttpServletResponse response, long retryAfter) {
        exceptionResolver.resolveException(
                request, response, null, new RateLimitExceededException(retryAfter));
    }

    /**
     * Behind Nginx, getRemoteAddr() would be the proxy for every request, so
     * one bucket would be shared by all users and the first busy client would
     * lock out everyone.
     *
     * The X-Forwarded-For handling is deliberately NOT done here. That header
     * is client-supplied: trusting it as written lets anyone bypass the limit
     * by sending a fresh value per request. Instead this relies on
     * server.forward-headers-strategy=framework, which installs Spring's
     * ForwardedHeaderFilter ahead of this one to rewrite getRemoteAddr(), and
     * on Nginx being configured to OVERWRITE X-Forwarded-For
     * (proxy_set_header X-Forwarded-For $remote_addr) rather than append to
     * whatever the client sent. Both halves are required; see
     * docs/decisions.md and deploy/nginx.conf.
     */
    private static String clientIp(HttpServletRequest request) {
        String remote = request.getRemoteAddr();
        return remote == null ? "unknown" : remote;
    }

    private static Supplier<BucketConfiguration> perMinute(int permits) {
        BucketConfiguration configuration = BucketConfiguration.builder()
                .addLimit(Bandwidth.builder()
                        .capacity(permits)
                        // Greedy refill drips tokens back continuously rather
                        // than all at once on a minute boundary, which stops
                        // every client synchronising into one burst per minute.
                        .refillGreedy(permits, Duration.ofMinutes(1))
                        .build())
                .build();
        return () -> configuration;
    }
}
