package com.akshatha.urlshortener;

import com.akshatha.urlshortener.repository.UrlMappingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end over real Postgres and real Redis, in the `cached` profile — the
 * M5 configuration. Skips itself when Docker is not running rather than
 * failing the build.
 *
 * This is the test that catches wiring mistakes the unit tests cannot: a
 * property that does not bind, a migration that does not match the entity, a
 * route that the greedy /{code} mapping has swallowed.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@org.springframework.test.context.ActiveProfiles("cached")
class ShortenerIntegrationTest {

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
        // The rate limiter would otherwise reject the loop below at request 11.
        registry.add("shortener.rate-limit.enabled", () -> false);
    }

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private UrlMappingRepository repository;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
        repository.deleteAll();
    }

    @Test
    void shortensAndThenRedirects() throws Exception {
        String code = mockMvc.perform(post("/api/v1/shorten")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"longUrl": "https://example.com/a/very/long/path"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.shortCode").value(matchesPattern("[0-9A-Za-z]{7}")))
                .andExpect(jsonPath("$.shortUrl").value(containsString("http://localhost:8080/")))
                .andExpect(jsonPath("$.longUrl").value("https://example.com/a/very/long/path"))
                .andReturn().getResponse().getContentAsString()
                .replaceAll(".*\"shortCode\"\\s*:\\s*\"([^\"]+)\".*", "$1");

        mockMvc.perform(get("/" + code))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "https://example.com/a/very/long/path"));

        // Second hit should come from Redis; same answer either way.
        mockMvc.perform(get("/" + code))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "https://example.com/a/very/long/path"));
    }

    @Test
    void firstCodeIsSevenCharactersBecauseOfTheSeed() throws Exception {
        mockMvc.perform(post("/api/v1/shorten")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"longUrl": "https://example.com/"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.shortCode").value(matchesPattern("[0-9A-Za-z]{7}")));
    }

    @Test
    void rejectsUnsafeUrlsWithACleanProblemDetail() throws Exception {
        for (String bad : new String[]{
                "javascript:alert(1)", "http://localhost/admin",
                "http://169.254.169.254/latest/meta-data/", "not-a-url"}) {

            mockMvc.perform(post("/api/v1/shorten")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"longUrl\": \"" + bad + "\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.title").value("Validation failed"))
                    .andExpect(jsonPath("$.errors.longUrl").exists());
        }
    }

    @Test
    void rejectsMalformedJson() throws Exception {
        mockMvc.perform(post("/api/v1/shorten")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Malformed request"));
    }

    @Test
    void unknownCodeIs404() throws Exception {
        mockMvc.perform(get("/zzzzzzz"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Short code not found"));
    }

    @Test
    void aTakenAliasIsAConflict() throws Exception {
        String body = """
                {"longUrl": "https://example.com/", "customAlias": "promo123"}""";

        mockMvc.perform(post("/api/v1/shorten")
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/shorten")
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Alias unavailable"));
    }

    /** A short URL that is dead on arrival should be refused, not minted. */
    @Test
    void anExpiryInThePastIsRejected() throws Exception {
        mockMvc.perform(post("/api/v1/shorten")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"longUrl": "https://example.com/", "expiresAt": "2020-01-01T00:00:00Z"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.expiresAt").exists());
    }

    /**
     * "actuator" and "error" are valid Base62 and would be accepted as
     * aliases, but /actuator and /error are answered by other mappings — so
     * the alias would be stored and then never resolve.
     */
    @Test
    void anAliasThatWouldBeShadowedByAnotherRouteIsRefused() throws Exception {
        for (String reserved : new String[]{"actuator", "error", "ACTUATOR"}) {
            mockMvc.perform(post("/api/v1/shorten")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"longUrl\": \"https://example.com/\", \"customAlias\": \""
                                    + reserved + "\"}"))
                    .andExpect(status().isConflict());
        }
    }

    /**
     * Regression tests for the catch-all swallowing Spring MVC's own
     * exceptions.
     *
     * Before GlobalExceptionHandler extended ResponseEntityExceptionHandler,
     * every one of these returned 500 and wrote a full stack trace to the
     * error log — so a crawler asking for /favicon.ico looked like an outage.
     */
    @Test
    void aPathThatMatchesNoRouteIs404NotA500() throws Exception {
        mockMvc.perform(get("/no-such-path")).andExpect(status().isNotFound());
        mockMvc.perform(get("/favicon.ico")).andExpect(status().isNotFound());
    }

    @Test
    void theWrongMethodOnARouteIs405NotA500() throws Exception {
        mockMvc.perform(post("/1000000")).andExpect(status().isMethodNotAllowed());
    }

    @Test
    void anUnsupportedContentTypeIs415NotA500() throws Exception {
        mockMvc.perform(post("/api/v1/shorten")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("https://example.com/"))
                .andExpect(status().isUnsupportedMediaType());
    }

    /**
     * The greedy-mapping regression test. An unconstrained /{code} at the
     * application root swallows these three, and the failure is silent until
     * someone opens Swagger in M6.
     */
    @Test
    void theRedirectMappingDoesNotSwallowActuatorOrSwagger() throws Exception {
        mockMvc.perform(get("/actuator/health")).andExpect(status().isOk());
        mockMvc.perform(get("/v3/api-docs")).andExpect(status().isOk());
        mockMvc.perform(get("/swagger-ui/index.html")).andExpect(status().isOk());
    }
}
