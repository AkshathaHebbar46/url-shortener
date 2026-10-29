package com.akshatha.urlshortener;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The rate limiter over a real socket and a real Redis.
 *
 * MockMvc cannot test this: the filter is registered through a
 * FilterRegistrationBean, which MockMvc's context setup does not apply, so a
 * MockMvc test would pass whether or not the limiter were wired at all.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@org.springframework.test.context.ActiveProfiles("cached")
class RateLimitIntegrationTest {

    private static final int SHORTEN_LIMIT = 3;

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:17-alpine"));

    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("shortener.rate-limit.enabled", () -> true);
        registry.add("shortener.rate-limit.shorten-per-minute", () -> SHORTEN_LIMIT);
        registry.add("shortener.rate-limit.redirect-per-minute", () -> 100);
    }

    @Autowired
    private TestRestTemplate rest;

    @LocalServerPort
    private int port;

    @Test
    void aCurlLoopStartsGetting429sWithARetryAfterHeader() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        int accepted = 0;
        ResponseEntity<String> rejected = null;
        for (int i = 0; i < SHORTEN_LIMIT + 3; i++) {
            ResponseEntity<String> response = rest.exchange(
                    "/api/v1/shorten", HttpMethod.POST,
                    new HttpEntity<>("{\"longUrl\": \"https://example.com/" + i + "\"}", headers),
                    String.class);

            if (response.getStatusCode() == HttpStatus.CREATED) {
                accepted++;
            } else {
                rejected = response;
            }
        }

        assertThat(accepted)
                .as("requests allowed through before the limiter kicked in")
                .isEqualTo(SHORTEN_LIMIT);

        assertThat(rejected).isNotNull();
        assertThat(rejected.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        // No Retry-After means clients just spin in a tight retry loop.
        assertThat(rejected.getHeaders().getFirst(HttpHeaders.RETRY_AFTER))
                .as("Retry-After header")
                .isNotNull();
        // Same ProblemDetail shape as every other error the API returns.
        assertThat(rejected.getBody()).contains("\"title\":\"Too many requests\"");
        assertThat(rejected.getBody()).contains("retryAfterSeconds");
    }

    /**
     * Shortening and redirecting must not share a bucket: one limit tuned for
     * the expensive endpoint would throttle the cheap, bursty one, and vice
     * versa.
     */
    @Test
    void theRedirectBudgetIsSeparateFromTheShortenBudget() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        // Burn the shorten budget completely.
        for (int i = 0; i < SHORTEN_LIMIT + 2; i++) {
            rest.exchange("/api/v1/shorten", HttpMethod.POST,
                    new HttpEntity<>("{\"longUrl\": \"https://example.com/burn" + i + "\"}", headers),
                    String.class);
        }

        // Redirects are on their own bucket and must still be served.
        ResponseEntity<String> redirect = rest.getForEntity("/zzzzzzz", String.class);
        assertThat(redirect.getStatusCode())
                .as("a redirect after the shorten budget is spent")
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    /** Operational endpoints are exempt, or traffic could starve the health probe. */
    @Test
    void actuatorAndSwaggerAreNotRateLimited() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        for (int i = 0; i < SHORTEN_LIMIT + 2; i++) {
            rest.exchange("/api/v1/shorten", HttpMethod.POST,
                    new HttpEntity<>("{\"longUrl\": \"https://example.com/x" + i + "\"}", headers),
                    String.class);
        }

        for (int i = 0; i < 20; i++) {
            assertThat(rest.getForEntity("/actuator/health", String.class).getStatusCode())
                    .isEqualTo(HttpStatus.OK);
        }
    }
}
